package com.grassland.marketplace.analytics;

import com.grassland.marketplace.commerce.NetSplitAllocation;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 任务书 #103 C103-15：统一经营事实查询（§6.6 口径，metricVersion=commerce-facts-v2）。
 *
 * <p>
 * 同一订单 cohort（windowBasis=order_created_at，[from,to)），截至 asOf：
 * <ul>
 * <li>订单数含未支付/取消；支付/退款/核销取权威事实（paid_at / consumer_order_refund 追加事实 /
 * redeemed_at），不用状态白名单；</li>
 * <li>已结三方收入只读 commerce_settlement_fact（经确认事实）；待结用 {@link NetSplitAllocation}
 * 按订单冻结额+累计退款逐单计算（已核销未分账且净额>0）；</li>
 * <li>退款先按订单聚合再连接（多笔退款不重复计人数、不与分账 JOIN 倍增）；</li>
 * <li>历史分账缺投影（已分账订单无事实行）→ dataCompleteness=partial +
 * missingSettlementFactCount，金额不把未知当已确认 0（§6.6 对外兼容规则；C16 据此 503）。</li>
 * </ul>
 */
@Component
public class CommerceFactsRepository {

	public static final String METRIC_VERSION = "commerce-facts-v2";
	public static final String WINDOW_BASIS = "order_created_at";

	private final DatabaseClient db;

	public CommerceFactsRepository(DatabaseClient db) {
		this.db = db;
	}

	/** §6.6 全字段的统一事实结果（金额均为分；meta 供响应/导出元信息与 503 判定）。 */
	public record Facts(String organizationId, String storeId, int orders, int paidOrders, long grossGmvCents,
			int refundedOrders, long refundedGmvCents, long netGmvCents, int redeemedOrders, long netRedeemedCents,
			long merchantRevenueCents, long platformFeeCents, long recommenderRevenueCents, long pendingMerchantCents,
			long pendingPlatformCents, long pendingRecommenderCents, long pendingHeldCents, long settledBountyCents,
			int settledOrders, int missingSettlementFactCount, int pendingOrders, Instant asOf,
			String dataCompleteness) {
	}

	/** 每推荐官已结分配（排行消费；settled 只认事实表）。 */
	public record RecommenderAllocation(String recommenderAccountId, long settledCents) {
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Mono<Facts> query(String organizationId, String storeId, Instant from, Instant to, Instant asOf) {
		if (organizationId == null || organizationId.isBlank()) {
			throw new IllegalArgumentException("organizationId 不能为空");
		}
		if (from != null && to != null && !from.isBefore(to)) {
			throw new IllegalArgumentException("时间窗非法：from 必须早于 to（[from,to)）");
		}
		Instant effectiveAsOf = asOf == null ? Instant.now() : asOf;
		Mono<Aggregate> aggregate = aggregateQuery(organizationId, storeId, from, to, effectiveAsOf);
		Mono<List<PendingOrder>> pendingOrders = pendingOrdersQuery(organizationId, storeId, from, to, effectiveAsOf);
		Mono<Long> settledBounty = settledBountyQuery(organizationId, storeId, from, to, effectiveAsOf);
		return aggregate.flatMap(a -> pendingOrders.flatMap(pending -> settledBounty.map(bounty -> {
			long pendingMerchant = 0;
			long pendingPlatform = 0;
			long pendingRecommender = 0;
			for (PendingOrder order : pending) {
				NetSplitAllocation.NetSplit net = NetSplitAllocation.allocate(order.priceCents(),
						order.recommenderAmountCents(), order.merchantAmountCents(), order.platformFeeCents(),
						order.refundedCents());
				pendingMerchant += net.merchantAmountCents();
				pendingPlatform += net.platformFeeCents();
				pendingRecommender += net.recommenderAmountCents();
			}
			String completeness = a.missingSettlementFactCount == 0 ? "complete" : "partial";
			return new Facts(organizationId, storeId, a.orders, a.paidOrders, a.grossGmvCents, a.refundedOrders,
					a.refundedGmvCents, a.grossGmvCents - a.refundedGmvCents, a.redeemedOrders, a.netRedeemedCents,
					a.merchantRevenueCents, a.platformFeeCents, a.recommenderRevenueCents, pendingMerchant,
					pendingPlatform, pendingRecommender, 0, bounty, a.settledOrders, a.missingSettlementFactCount,
					pending.size(), effectiveAsOf, completeness);
		})));
	}

	/** 推荐官已结分配聚合（先聚合 allocation 再与 cohort 连接，避免金额倍增）。 */
	public Flux<RecommenderAllocation> recommenderSettled(String organizationId, String storeId, Instant from,
			Instant to, Instant asOf) {
		var spec = db.sql("""
				WITH cohort AS (
				    SELECT id, split_completed_at FROM consumer_order
				    WHERE organization_id = CAST(:org AS uuid)
				      AND (:store IS NULL OR store_id = CAST(:store AS uuid))
				      AND (:fromAt IS NULL OR created_at >= :fromAt)
				      AND (:toAt IS NULL OR created_at < :toAt)
				      AND created_at < :asOf
				)
				SELECT a.recommender_account_id::text id, SUM(a.amount_cents)::bigint settled
				FROM commerce_settlement_allocation_fact a
				JOIN cohort c ON c.id = a.order_id
				JOIN commerce_settlement_fact f ON f.order_id = a.order_id
				WHERE COALESCE(f.finance_completed_at, c.split_completed_at, f.verified_at) < :asOf
				GROUP BY 1 ORDER BY settled DESC
				""").bind("org", organizationId).bind("asOf", asOf.atOffset(ZoneOffset.UTC));
		spec = bindScope(spec, storeId, from, to);
		return spec.map((row, meta) -> new RecommenderAllocation(row.get("id", String.class),
				value(row.get("settled", Long.class)))).all();
	}

	/** 按北京时间桶位的日/周/月序列（订单侧事实口径；退款按订单创建桶重述）。 */
	public Flux<Bucket> seriesBuckets(String organizationId, String storeId, Instant from, Instant to,
			String granularity, Instant asOf) {
		Instant effectiveAsOf = asOf == null ? Instant.now() : asOf;
		var spec = db.sql("""
				WITH cohort AS (
				    SELECT id, price_cents, paid_at, redeemed_at, split_completed_at, created_at
				    FROM consumer_order
				    WHERE organization_id = CAST(:org AS uuid)
				      AND (:store IS NULL OR store_id = CAST(:store AS uuid))
				      AND created_at >= :fromAt AND created_at < :toAt AND created_at < :asOf
				), refund_series AS (
				    SELECT c.id, SUM(r.amount_cents) refunded
				    FROM cohort c
				    LEFT JOIN consumer_order_refund r ON r.order_id = c.id AND r.occurred_at < :asOf
				    GROUP BY c.id
				)
				SELECT to_char(date_trunc(CAST(:field AS text), c.created_at AT TIME ZONE 'Asia/Shanghai'),
				               'YYYY-MM-DD') bucket,
				       COUNT(*)::int orders,
				       COUNT(*) FILTER (WHERE c.paid_at < :asOf)::int paid,
				       COUNT(*) FILTER (WHERE c.redeemed_at < :asOf)::int redeemed,
				       COUNT(*) FILTER (WHERE rs.refunded > 0 AND c.paid_at < :asOf)::int refunded,
				       COALESCE(SUM(c.price_cents) FILTER (WHERE c.paid_at < :asOf), 0)::bigint gross,
				       COALESCE(SUM(rs.refunded) FILTER (WHERE c.paid_at < :asOf), 0)::bigint refund_gmv,
				       COALESCE(SUM(f.merchant_cents), 0)::bigint merchant_revenue,
				       COALESCE(SUM(f.recommender_total_cents), 0)::bigint recommender_revenue
				FROM cohort c
				JOIN refund_series rs ON rs.id = c.id
				LEFT JOIN commerce_settlement_fact f ON f.order_id = c.id
				    AND COALESCE(f.finance_completed_at, c.split_completed_at, f.verified_at) < :asOf
				GROUP BY 1 ORDER BY 1
				""").bind("org", organizationId).bind("field", granularity)
				.bind("fromAt", from.atOffset(ZoneOffset.UTC)).bind("toAt", to.atOffset(ZoneOffset.UTC))
				.bind("asOf", effectiveAsOf.atOffset(ZoneOffset.UTC));
		spec = bindNullableStore(spec, storeId);
		return spec.map((row, meta) -> new Bucket(row.get("bucket", String.class),
				integer(row.get("orders", Integer.class)), integer(row.get("paid", Integer.class)),
				integer(row.get("redeemed", Integer.class)), integer(row.get("refunded", Integer.class)),
				value(row.get("gross", Long.class)), value(row.get("refund_gmv", Long.class)),
				value(row.get("merchant_revenue", Long.class)), value(row.get("recommender_revenue", Long.class))))
				.all();
	}

	public record Bucket(String bucket, int orders, int paid, int redeemed, int refunded, long grossGmvCents,
			long refundedGmvCents, long merchantRevenueCents, long recommenderRevenueCents) {
	}

	// ---------- 内部查询 ----------

	private record Aggregate(int orders, int paidOrders, long grossGmvCents, int refundedOrders, long refundedGmvCents,
			int redeemedOrders, long netRedeemedCents, long merchantRevenueCents, long platformFeeCents,
			long recommenderRevenueCents, int settledOrders, int missingSettlementFactCount) {
	}

	private record PendingOrder(long priceCents, long recommenderAmountCents, long merchantAmountCents,
			long platformFeeCents, long refundedCents) {
	}

	private Mono<Aggregate> aggregateQuery(String organizationId, String storeId, Instant from, Instant to,
			Instant asOf) {
		var spec = db.sql("""
				WITH cohort AS (
				    SELECT o.id, o.price_cents, o.paid_at, o.redeemed_at, o.split_completed_at
				    FROM consumer_order o
				    WHERE o.organization_id = CAST(:org AS uuid)
				      AND (:store IS NULL OR o.store_id = CAST(:store AS uuid))
				      AND (:fromAt IS NULL OR o.created_at >= :fromAt)
				      AND (:toAt IS NULL OR o.created_at < :toAt)
				    AND o.created_at < :asOf
				), refunds AS (
				    SELECT r.order_id, SUM(r.amount_cents) refunded
				    FROM consumer_order_refund r
				    JOIN cohort c ON c.id = r.order_id
				    WHERE r.occurred_at < :asOf
				    GROUP BY r.order_id
				)
				SELECT COUNT(*)::int orders,
				       COUNT(*) FILTER (WHERE c.paid_at < :asOf)::int paid,
				       COALESCE(SUM(c.price_cents) FILTER (WHERE c.paid_at < :asOf), 0)::bigint gross,
				       COUNT(*) FILTER (WHERE rs.refunded > 0 AND c.paid_at < :asOf)::int refunded_orders,
				       COALESCE(SUM(rs.refunded) FILTER (WHERE c.paid_at < :asOf), 0)::bigint refund_gmv,
				       COUNT(*) FILTER (WHERE c.redeemed_at < :asOf)::int redeemed,
				       COALESCE(SUM(c.price_cents - COALESCE(rs.refunded, 0))
				                FILTER (WHERE c.redeemed_at < :asOf AND c.paid_at < :asOf), 0)::bigint net_redeemed,
				       COALESCE(SUM(f.merchant_cents), 0)::bigint merchant_revenue,
				       COALESCE(SUM(f.platform_cents), 0)::bigint platform_fee,
				       COALESCE(SUM(f.recommender_total_cents), 0)::bigint recommender_revenue,
				       COUNT(*) FILTER (WHERE f.order_id IS NOT NULL)::int settled_orders,
				       COUNT(*) FILTER (WHERE c.split_completed_at < :asOf AND f.order_id IS NULL)::int missing_facts
				FROM cohort c
				LEFT JOIN refunds rs ON rs.order_id = c.id
				LEFT JOIN commerce_settlement_fact f ON f.order_id = c.id
				  AND COALESCE(f.finance_completed_at, c.split_completed_at, f.verified_at) < :asOf
				""").bind("org", organizationId).bind("asOf", asOf.atOffset(ZoneOffset.UTC));
		spec = bindScope(spec, storeId, from, to);
		return spec.map((row, meta) -> new Aggregate(integer(row.get("orders", Integer.class)),
				integer(row.get("paid", Integer.class)), value(row.get("gross", Long.class)),
				integer(row.get("refunded_orders", Integer.class)), value(row.get("refund_gmv", Long.class)),
				integer(row.get("redeemed", Integer.class)), value(row.get("net_redeemed", Long.class)),
				value(row.get("merchant_revenue", Long.class)), value(row.get("platform_fee", Long.class)),
				value(row.get("recommender_revenue", Long.class)), integer(row.get("settled_orders", Integer.class)),
				integer(row.get("missing_facts", Integer.class)))).one()
				.defaultIfEmpty(new Aggregate(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
	}

	/** 待结订单（已核销未分账且净额>0）——逐单取冻结额+累计退款，Java 侧 NetSplitAllocation 计算。 */
	private Mono<List<PendingOrder>> pendingOrdersQuery(String organizationId, String storeId, Instant from, Instant to,
			Instant asOf) {
		var spec = db.sql("""
				WITH cohort AS (
				    SELECT o.id
				    FROM consumer_order o
				    WHERE o.organization_id = CAST(:org AS uuid)
				      AND (:store IS NULL OR o.store_id = CAST(:store AS uuid))
				      AND (:fromAt IS NULL OR o.created_at >= :fromAt)
				      AND (:toAt IS NULL OR o.created_at < :toAt)
				      AND o.created_at < :asOf
				), refunds AS (
				    SELECT r.order_id, SUM(r.amount_cents) refunded
				    FROM consumer_order_refund r JOIN cohort c ON c.id = r.order_id
				    WHERE r.occurred_at < :asOf
				    GROUP BY r.order_id
				)
				SELECT o.price_cents, o.recommender_amount_cents, o.merchant_amount_cents, o.platform_fee_cents,
				       COALESCE(rs.refunded, 0) refunded
				FROM consumer_order o
				JOIN cohort c ON c.id = o.id
				LEFT JOIN refunds rs ON rs.order_id = o.id
				LEFT JOIN commerce_settlement_fact f ON f.order_id = o.id
				    AND COALESCE(f.finance_completed_at, o.split_completed_at, f.verified_at) < :asOf
				WHERE o.redeemed_at < :asOf
				  AND o.paid_at < :asOf
				  AND (o.split_completed_at IS NULL OR o.split_completed_at >= :asOf)
				  AND f.order_id IS NULL
				  AND o.price_cents - COALESCE(rs.refunded, 0) > 0
				""").bind("org", organizationId).bind("asOf", asOf.atOffset(ZoneOffset.UTC));
		spec = bindScope(spec, storeId, from, to);
		return spec
				.map((row, meta) -> new PendingOrder(value(row.get("price_cents", Long.class)),
						value(row.get("recommender_amount_cents", Long.class)),
						value(row.get("merchant_amount_cents", Long.class)),
						value(row.get("platform_fee_cents", Long.class)), value(row.get("refunded", Long.class))))
				.all().collectList().defaultIfEmpty(List.of());
	}

	/** 原任务结算事实（settled bounty，独立时间轴不入消费分账）。 */
	private Mono<Long> settledBountyQuery(String organizationId, String storeId, Instant from, Instant to,
			Instant asOf) {
		var spec = db.sql("""
				SELECT COALESCE(SUM(f.bounty_cents), 0)::bigint value FROM (
				    SELECT DISTINCT a.id, a.bounty_cents
				    FROM task_application a JOIN task t ON t.id = a.task_id
				    JOIN marketplace_outbox o ON o.aggregate_id = a.id::text AND o.event_type = 'EngagementSettled'
				    WHERE t.organization_id = CAST(:org AS uuid)
				      AND (:store IS NULL OR t.store_id = CAST(:store AS uuid))
				      AND (:fromAt IS NULL OR o.created_at >= :fromAt)
				      AND (:toAt IS NULL OR o.created_at < :toAt)
				      AND o.created_at < :asOf
				) f
				""").bind("org", organizationId).bind("asOf", asOf.atOffset(ZoneOffset.UTC));
		spec = bindScope(spec, storeId, from, to);
		return spec.map((row, meta) -> value(row.get("value", Long.class))).one().defaultIfEmpty(0L);
	}

	private static GenericExecuteSpec bindScope(GenericExecuteSpec spec, String storeId, Instant from, Instant to) {
		spec = bindNullableStore(spec, storeId);
		if (from == null) {
			spec = spec.bindNull("fromAt", OffsetDateTime.class);
		} else {
			spec = spec.bind("fromAt", from.atOffset(ZoneOffset.UTC));
		}
		if (to == null) {
			spec = spec.bindNull("toAt", OffsetDateTime.class);
		} else {
			spec = spec.bind("toAt", to.atOffset(ZoneOffset.UTC));
		}
		return spec;
	}

	private static GenericExecuteSpec bindNullableStore(GenericExecuteSpec spec, String storeId) {
		return storeId == null || storeId.isBlank()
				? spec.bindNull("store", String.class)
				: spec.bind("store", storeId);
	}

	private static long value(Long value) {
		return value == null ? 0L : value;
	}

	private static int integer(Integer value) {
		return value == null ? 0 : value;
	}
}
