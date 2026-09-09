package com.grassland.marketplace.commerce;

import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.springframework.transaction.reactive.TransactionalOperator;

/**
 * 任务书 #98 C98-05 / D98-05：异常订单自动标记 + 人工确认暂扣。
 *
 * <p>
 * 自动标记只写 flagged 候选行（唯一索引幂等、不碰钱）；人工确认 → held（结算挂起 + 处理期限， 默认 72h
 * 可配）；解除/驳回同样人工。规则阈值全部配置化，改阈值不影响已落行事实。
 */
@Component
public class OpsOrderHoldService {

	private final OpsOrderHoldRepository holds;
	private final DatabaseClient db;
	private final CommerceRepository commerce;
	private final TransactionalOperator transactions;
	private final long refundRateWindowDays;
	private final int refundRateMinOrders;
	private final int refundRateWatchBps;
	private final long appealBurstWindowDays;
	private final int appealBurstMinAppeals;
	private final long rlidBurstWindowDays;
	private final int rlidBurstMaxOrders;
	private final long holdDeadlineHours;

	public OpsOrderHoldService(OpsOrderHoldRepository holds, DatabaseClient db, CommerceRepository commerce,
			@Value("${marketplace.ops.order-hold-refund-rate-window-days:7}") long refundRateWindowDays,
			@Value("${marketplace.ops.order-hold-refund-rate-min-orders:5}") int refundRateMinOrders,
			@Value("${marketplace.ops.order-hold-refund-rate-watch-bps:4000}") int refundRateWatchBps,
			@Value("${marketplace.ops.order-hold-appeal-burst-window-days:3}") long appealBurstWindowDays,
			@Value("${marketplace.ops.order-hold-appeal-burst-min-appeals:3}") int appealBurstMinAppeals,
			@Value("${marketplace.ops.order-hold-rlid-burst-window-days:1}") long rlidBurstWindowDays,
			@Value("${marketplace.ops.order-hold-rlid-burst-max-orders:10}") int rlidBurstMaxOrders,
			@Value("${marketplace.ops.order-hold-deadline-hours:72}") long holdDeadlineHours,
			TransactionalOperator transactions) {
		this.holds = holds;
		this.db = db;
		this.commerce = commerce;
		this.transactions = transactions;
		this.refundRateWindowDays = Math.max(refundRateWindowDays, 1);
		this.refundRateMinOrders = Math.max(refundRateMinOrders, 1);
		this.refundRateWatchBps = Math.max(refundRateWatchBps, 1);
		this.appealBurstWindowDays = Math.max(appealBurstWindowDays, 1);
		this.appealBurstMinAppeals = Math.max(appealBurstMinAppeals, 1);
		this.rlidBurstWindowDays = Math.max(rlidBurstWindowDays, 1);
		this.rlidBurstMaxOrders = Math.max(rlidBurstMaxOrders, 1);
		this.holdDeadlineHours = Math.max(holdDeadlineHours, 1);
	}

	long holdDeadlineHours() {
		return holdDeadlineHours;
	}

	/** 三条规则扫描（dispatcher 与 IT seam 共用）：返回本次新标记的 flagged 行。 */
	public Flux<OpsOrderHoldRepository.HoldRow> evaluateRules(int limit) {
		return Flux.concat(refundRateRule(limit), appealBurstRule(limit), rlidBurstRule(limit));
	}

	/** 规则 1 referral_refund_rate：同推荐官近窗归因订单退款率 ≥ watch 阈值 → 标记其未退款在途单。 */
	private Flux<OpsOrderHoldRepository.HoldRow> refundRateRule(int limit) {
		return db.sql("""
				WITH agg AS (
				    SELECT o.recommender_account_id AS rec, count(*) AS total,
				           count(*) FILTER (WHERE o.refunded_amount_cents > 0) AS refunded
				      FROM consumer_order o
				     WHERE o.recommender_account_id IS NOT NULL
				       AND o.created_at > now() - (:days || ' days')::interval
				     GROUP BY o.recommender_account_id
				    HAVING count(*) >= :minOrders
				       AND count(*) FILTER (WHERE o.refunded_amount_cents > 0) * 10000 >= :watchBps * count(*)
				)
				SELECT o.id AS order_id, agg.total, agg.refunded
				  FROM consumer_order o JOIN agg ON agg.rec = o.recommender_account_id
				 WHERE o.refunded_amount_cents = 0
				   AND o.status IN ('paid', 'redeeming', 'redeemed', 'partially_refunded')
				   -- 审查修复 01（R07）：已有未终态标记行的候选不再占据扫描 LIMIT（重复扫描幂等空转
				   -- 会让新候选永远进不来），dismissed/released 终态行不受限。
				   AND NOT EXISTS (SELECT 1 FROM ops_order_hold h WHERE h.order_id = o.id
				                   AND h.status IN ('flagged', 'held'))
				 LIMIT :lim
				""").bind("days", String.valueOf(refundRateWindowDays)).bind("minOrders", refundRateMinOrders)
				.bind("watchBps", refundRateWatchBps).bind("lim", limit)
				.map((row, meta) -> new RuleCandidate(row.get("order_id", UUID.class),
						String.format("推荐官近 %d 天归因订单退款率 %.0f%%（%d/%d）≥ 阈值 %.0f%%", refundRateWindowDays,
								row.get("refunded", Long.class) * 100.0 / row.get("total", Long.class),
								row.get("refunded", Long.class), row.get("total", Long.class),
								refundRateWatchBps / 100.0)))
				.all().flatMap(candidate -> holds.insertFlagged(candidate.orderId(), "referral_refund_rate",
						candidate.reason()));
	}

	/** 规则 2 appeal_burst：同推荐官近窗被归因申诉 ≥ N 次 → 标记其未退款在途单。 */
	private Flux<OpsOrderHoldRepository.HoldRow> appealBurstRule(int limit) {
		return db.sql("""
				WITH agg AS (
				    SELECT a.claimed_recommender_account_id AS rec, count(*) AS appeals
				      FROM consumer_order_attribution_appeal a
				     WHERE a.created_at > now() - (:days || ' days')::interval
				     GROUP BY a.claimed_recommender_account_id
				    HAVING count(*) >= :minAppeals
				)
				SELECT o.id AS order_id, agg.appeals
				  FROM consumer_order o JOIN agg ON agg.rec = o.recommender_account_id
				 WHERE o.refunded_amount_cents = 0
				   AND o.status IN ('paid', 'redeeming', 'redeemed', 'partially_refunded')
				   -- 审查修复 01（R07）：已有未终态标记行的候选不再占据扫描 LIMIT（重复扫描幂等空转
				   -- 会让新候选永远进不来），dismissed/released 终态行不受限。
				   AND NOT EXISTS (SELECT 1 FROM ops_order_hold h WHERE h.order_id = o.id
				                   AND h.status IN ('flagged', 'held'))
				 LIMIT :lim
				""").bind("days", String.valueOf(appealBurstWindowDays)).bind("minAppeals", appealBurstMinAppeals)
				.bind("lim", limit)
				.map((row, meta) -> new RuleCandidate(row.get("order_id", UUID.class),
						String.format("推荐官近 %d 天被归因申诉 %d 次 ≥ 阈值 %d 次", appealBurstWindowDays,
								row.get("appeals", Long.class), appealBurstMinAppeals)))
				.all()
				.flatMap(candidate -> holds.insertFlagged(candidate.orderId(), "appeal_burst", candidate.reason()));
	}

	/** 规则 3 rlid_order_burst：同 rlid 近窗归因订单 > M 单 → 标记该链接的未退款在途单。 */
	private Flux<OpsOrderHoldRepository.HoldRow> rlidBurstRule(int limit) {
		return db.sql("""
				WITH agg AS (
				    SELECT t.referral_link_id AS rlid, count(*) AS orders
				      FROM consumer_order_attribution t
				     WHERE t.referral_link_id IS NOT NULL
				       AND t.created_at > now() - (:days || ' days')::interval
				     GROUP BY t.referral_link_id
				    HAVING count(*) > :maxOrders
				)
				SELECT o.id AS order_id, agg.orders
				  FROM consumer_order_attribution t
				  JOIN agg ON agg.rlid = t.referral_link_id
				  JOIN consumer_order o ON o.id = t.order_id
				 WHERE o.refunded_amount_cents = 0
				   AND o.status IN ('paid', 'redeeming', 'redeemed', 'partially_refunded')
				   -- 审查修复 01（R07）：已有未终态标记行的候选不再占据扫描 LIMIT（重复扫描幂等空转
				   -- 会让新候选永远进不来），dismissed/released 终态行不受限。
				   AND NOT EXISTS (SELECT 1 FROM ops_order_hold h WHERE h.order_id = o.id
				                   AND h.status IN ('flagged', 'held'))
				 LIMIT :lim
				""").bind("days", String.valueOf(rlidBurstWindowDays)).bind("maxOrders", rlidBurstMaxOrders)
				.bind("lim", limit)
				.map((row, meta) -> new RuleCandidate(row.get("order_id", UUID.class),
						String.format("同一推广链接近 %d 天归因订单 %d 单 > 阈值 %d 单", rlidBurstWindowDays,
								row.get("orders", Long.class), rlidBurstMaxOrders)))
				.all()
				.flatMap(candidate -> holds.insertFlagged(candidate.orderId(), "rlid_order_burst", candidate.reason()));
	}

	private record RuleCandidate(UUID orderId, String reason) {
	}

	// ---------- 人工动作（AC-98-21/22/23） ----------

	public Flux<OpsOrderHoldRepository.HoldRow> queue(String status) {
		return holds.listQueue(status, 200);
	}

	public Flux<OpsOrderHoldRepository.HoldRow> overdueQueue() {
		return holds.listOverdue(Instant.now());
	}

	/**
	 * 确认暂扣：flagged → held + 处理期限；重复确认/终态 409。审查修复 01（C01-C）：订单分账占位
	 * （splitting，资金在途）期间确认同样 409——不能谎称暂扣已阻断出款，占位释放后重试即成功。
	 */
	public Mono<OpsOrderHoldRepository.HoldRow> confirm(Caller operator, UUID holdId) {
		return holds.find(holdId).switchIfEmpty(Mono.error(new MarketplaceException(404, "标记不存在")))
				.flatMap(found -> holds
						.confirm(holdId, UUID.fromString(operator.accountId()),
								Instant.now().plus(Duration.ofHours(holdDeadlineHours)))
						.switchIfEmpty(
								Mono.error(new MarketplaceException(409, "该标记已被处理，或订单分账处理中（资金在途，暂无法确认暂扣），请稍后重试"))));
	}

	/** 解除：held → released（结算恢复）；附带解除说明。 */
	public Mono<OpsOrderHoldRepository.HoldRow> release(Caller operator, UUID holdId, String note) {
		if (note == null || note.isBlank()) {
			return Mono.error(new MarketplaceException(400, "解除说明必填"));
		}
		return holds.find(holdId).switchIfEmpty(Mono.error(new MarketplaceException(404, "标记不存在")))
				.flatMap(found -> holds.release(holdId, UUID.fromString(operator.accountId()), note)
						.switchIfEmpty(Mono.error(new MarketplaceException(409, "仅暂扣中的标记可解除"))));
	}

	/** 驳回标记：flagged → dismissed（不构成暂扣、不影响结算）。 */
	public Mono<OpsOrderHoldRepository.HoldRow> dismiss(Caller operator, UUID holdId) {
		return holds.find(holdId).switchIfEmpty(Mono.error(new MarketplaceException(404, "标记不存在")))
				.flatMap(found -> holds.dismiss(holdId, UUID.fromString(operator.accountId()))
						.switchIfEmpty(Mono.error(new MarketplaceException(409, "该标记已被处理"))));
	}

	// ---------- 经营看板（D98-06） ----------

	public record DashboardMetric(String key, String label, long valueCents, String source, String windowNote,
			String note) {
	}

	public Mono<Map<String, Object>> dashboard(int windowDays) {
		int days = Math.max(1, Math.min(windowDays, 365));
		Instant asOf = Instant.now();
		Instant from = asOf.minus(Duration.ofDays(days));
		Mono<long[]> windowed = db.sql("""
				SELECT
				  COALESCE(SUM(price_cents) FILTER (WHERE recommender_account_id IS NOT NULL
				      AND paid_at >= :fromAt AND paid_at < :toAt), 0) AS attributed_sales,
				  COALESCE((SELECT SUM(r.amount_cents) FROM consumer_order_refund r
				      WHERE r.occurred_at >= :fromAt AND r.occurred_at < :toAt), 0)
				  + COALESCE((SELECT SUM(o.refunded_amount_cents) FROM consumer_order o
				      WHERE o.refunded_at >= :fromAt AND o.refunded_at < :toAt
				        AND o.refunded_amount_cents > 0
				        AND NOT EXISTS (SELECT 1 FROM consumer_order_refund r WHERE r.order_id = o.id)), 0) AS refunded
				  FROM consumer_order
				""").bind("fromAt", from.atOffset(ZoneOffset.UTC)).bind("toAt", asOf.atOffset(ZoneOffset.UTC)).map(
				(row, meta) -> new long[]{row.get("attributed_sales", Long.class), row.get("refunded", Long.class)})
				.one();
		Mono<long[]> realtime = commerce.dashboardOrders().collectList().map(rows -> {
			long pending = 0L;
			long settled = 0L;
			for (CommerceRepository.DashboardOrder row : rows) {
				NetSplitAllocation.NetSplit net = NetSplitAllocation.allocate(row.priceCents(),
						row.recommenderAmountCents(), row.merchantAmountCents(), row.platformFeeCents(),
						row.refundedAmountCents());
				if (row.settled()) {
					settled = Math.addExact(settled, net.recommenderAmountCents());
				} else {
					pending = Math.addExact(pending, net.recommenderAmountCents());
				}
			}
			return new long[]{pending, settled};
		});
		Mono<Map<String, Object>> result = windowed.flatMap(window -> realtime.map(realtimeValues -> {
			long attributedSales = window[0];
			long refunded = window[1];
			long pending = realtimeValues[0];
			long settled = realtimeValues[1];
			long netCommission = settled + pending;
			List<DashboardMetric> metrics = List.of(
					new DashboardMetric("attributedSalesCents", "归因实付成交额", attributedSales, "consumer_order.paid_at",
							"近 " + days + " 天", "成功支付事实入窗；窗口时区 Asia/Shanghai；后续退款不抹掉原成交；归因不宣称增量收益"),
					new DashboardMetric("refundedNetCents", "退款金额", refunded, "consumer_order_refund.occurred_at",
							"近 " + days + " 天", "窗口时区 Asia/Shanghai；按每次已确认退款发生时间累计；旧记录无明细时仅用 refunded_at 回退"),
					new DashboardMetric("pendingSettleCents", "待结佣金", pending, "订单净额分配（split_completed_at IS NULL）",
							"截至 " + asOf, "包含已核销未分账及 held 暂扣的结算义务"),
					new DashboardMetric("settledCents", "已结佣金", settled, "订单净额分配（split_completed_at）", "截至 " + asOf,
							"按实际分账事实累计，含合法历史冲正后的净额"),
					new DashboardMetric("netCommissionCents", "净佣金（含待结）", netCommission, "待结佣金 + 已结佣金", "截至 " + asOf,
							"已包含有效退款/冲正影响，不重复扣减原订单佣金"));
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("windowDays", days);
			body.put("from", from.toString());
			body.put("to", asOf.toString());
			body.put("asOf", asOf.toString());
			body.put("timezone", "Asia/Shanghai");
			body.put("metrics", metrics.stream().map(metric -> {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("key", metric.key());
				row.put("label", metric.label());
				row.put("valueCents", metric.valueCents());
				row.put("source", metric.source());
				row.put("window", metric.windowNote());
				row.put("note", metric.note());
				return row;
			}).toList());
			body.put("computedAt", asOf.toString());
			return body;
		}));
		return transactions.transactional(result);
	}
}
