package com.grassland.marketplace.commerce;

import static com.grassland.marketplace.commerce.CommerceRepository.ORDER_COLS;
import static com.grassland.marketplace.commerce.CommerceRepository.VERSION_COLS;
import static com.grassland.marketplace.commerce.CommerceRepository.bounded;
import static com.grassland.marketplace.commerce.CommerceRepository.instant;

import com.grassland.marketplace.commerce.CommerceModels.AfterSalesDispute;
import com.grassland.marketplace.commerce.CommerceModels.AttributionAppeal;
import com.grassland.marketplace.commerce.CommerceModels.OfferVersion;
import com.grassland.marketplace.commerce.CommerceModels.Order;
import com.grassland.marketplace.commerce.CommerceModels.Review;
import com.grassland.marketplace.commerce.CommerceRepository.AttributionAllocation;
import com.grassland.marketplace.commerce.CommerceRepository.DashboardOrder;
import com.grassland.marketplace.commerce.CommerceRepository.MerchantPromotion;
import com.grassland.marketplace.commerce.CommerceRepository.RecommenderPromotion;
import com.grassland.marketplace.commerce.CommerceRepository.ReferralAttributionFact;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 任务书 #103 C103-21：consumer_order 读侧（单条/消费者/商家/导出/治理台分页与计数/核销视图/dispatcher 扫描/
 * 归因与申诉读取/推广漏斗/看板事实）——自 {@link CommerceRepository} 按职责原样搬移，SQL 与口径零变更。
 */
@Component
public class CommerceOrderQueryRepository {

	/**
	 * Read-side enrichment so orders expose the booked time slot without trusting
	 * current package versions.
	 */
	private static final String ORDER_SLOT_COLS = ", s.slot_start AS slot_start, s.slot_end AS slot_end";
	private static final String ORDER_SLOT_JOIN = " LEFT JOIN commerce_package_inventory_slot s ON s.id = o.inventory_slot_id";
	private static final String REDEMPTION_STATUSES_PREDICATE = " WHERE o.status IN ('redeeming', 'redeemed')";

	private final DatabaseClient db;

	public CommerceOrderQueryRepository(DatabaseClient db) {
		this.db = db;
	}

	public Mono<Order> findOrder(String id) {
		return db
				.sql("SELECT " + ORDER_COLS + ORDER_SLOT_COLS + " FROM consumer_order o" + ORDER_SLOT_JOIN
						+ " WHERE o.id = CAST(:id AS uuid)")
				.bind("id", id).map(CommerceRepository::mapOrderWithSlot).one();
	}

	public Mono<Order> findOrderByCodeHash(String hash) {
		return db
				.sql("SELECT " + ORDER_COLS + ORDER_SLOT_COLS + " FROM consumer_order o" + ORDER_SLOT_JOIN
						+ " WHERE o.redeem_code_hash = :hash")
				.bind("hash", hash).map(CommerceRepository::mapOrderWithSlot).one();
	}

	public Flux<Order> listConsumerOrders(String accountId, int limit) {
		return db
				.sql("SELECT " + ORDER_COLS + ORDER_SLOT_COLS + " FROM consumer_order o" + ORDER_SLOT_JOIN
						+ " WHERE o.consumer_account_id = CAST(:accountId AS uuid)"
						+ " ORDER BY o.created_at DESC LIMIT :limit")
				.bind("accountId", accountId).bind("limit", bounded(limit)).map(CommerceRepository::mapOrderWithSlot)
				.all();
	}

	public Flux<Order> listMerchantOrders(String organizationId, String storeId, int limit) {
		// 任务书 #77 卡 B（D2）连带：storeId 是可选过滤，不传 = 组织全量视角——订单随套餐门店落库，
		// 旧「不传 = store_id IS NULL」会让门店级订单从商家订单面板消失。
		String storePredicate = storeId == null || storeId.isBlank() ? "" : " AND o.store_id = CAST(:store AS uuid)";
		GenericExecuteSpec spec = db
				.sql("SELECT " + ORDER_COLS + ORDER_SLOT_COLS + " FROM consumer_order o" + ORDER_SLOT_JOIN
						+ " WHERE o.organization_id = CAST(:org AS uuid)" + storePredicate
						+ " ORDER BY o.created_at DESC LIMIT :limit")
				.bind("org", organizationId).bind("limit", bounded(limit));
		if (storeId != null && !storeId.isBlank())
			spec = spec.bind("store", storeId);
		return spec.map(CommerceRepository::mapOrderWithSlot).all();
	}

	/**
	 * Bounded merchant export. Authorization is completed by the service before
	 * this query runs.
	 */
	public Flux<Order> exportMerchantOrders(String organizationId, String storeId, String status, Instant from,
			Instant to, int limit) {
		// 同 listMerchantOrders：不传 storeId = 组织全量（谓词前缀式拼接，空过滤不残留悬挂 AND）。
		String storePredicate = storeId == null || storeId.isBlank() ? "" : " AND o.store_id = CAST(:store AS uuid)";
		StringBuilder predicates = new StringBuilder(" WHERE o.organization_id = CAST(:org AS uuid)")
				.append(storePredicate);
		if (status != null && !status.isBlank())
			predicates.append(" AND o.status = :status");
		if (from != null)
			predicates.append(" AND o.created_at >= :fromAt");
		if (to != null)
			predicates.append(" AND o.created_at < :toAt");
		GenericExecuteSpec spec = db
				.sql("SELECT " + ORDER_COLS + ORDER_SLOT_COLS + " FROM consumer_order o" + ORDER_SLOT_JOIN + predicates
						+ " ORDER BY o.created_at DESC LIMIT :limit")
				.bind("org", organizationId).bind("limit", Math.max(1, Math.min(limit, 10_000)));
		if (storeId != null && !storeId.isBlank())
			spec = spec.bind("store", storeId);
		if (status != null && !status.isBlank())
			spec = spec.bind("status", status);
		if (from != null)
			spec = spec.bind("fromAt", from.atOffset(ZoneOffset.UTC));
		if (to != null)
			spec = spec.bind("toAt", to.atOffset(ZoneOffset.UTC));
		return spec.map(CommerceRepository::mapOrderWithSlot).all();
	}

	public Flux<Order> listAdminOrders(String status, int limit, int offset) {
		String predicate = status == null || status.isBlank() ? "" : " WHERE o.status = :status";
		GenericExecuteSpec spec = db
				.sql("SELECT " + ORDER_COLS + ORDER_SLOT_COLS + " FROM consumer_order o" + ORDER_SLOT_JOIN + predicate
						+ " ORDER BY o.created_at DESC LIMIT :limit OFFSET :offset")
				.bind("limit", bounded(limit)).bind("offset", Math.max(0, offset));
		if (!predicate.isEmpty())
			spec = spec.bind("status", status);
		return spec.map(CommerceRepository::mapOrderWithSlot).all();
	}

	/**
	 * 任务书 #53：与 {@link #listAdminOrders} 同 WHERE 口径的 COUNT（无 ORDER BY / LIMIT /
	 * OFFSET）——信封 total。
	 */
	public Mono<Integer> countAdminOrders(String status) {
		String predicate = status == null || status.isBlank() ? "" : " WHERE o.status = :status";
		GenericExecuteSpec spec = db.sql("SELECT COUNT(*)::int AS c FROM consumer_order o" + predicate);
		if (!predicate.isEmpty())
			spec = spec.bind("status", status);
		return spec.map(row -> row.get("c", Integer.class)).one();
	}

	/**
	 * 任务书 #53：核销视图单条查询（替代原两次查询内存拼接）：{@code status IN ('redeeming','redeemed')} 统一
	 * {@code created_at DESC} 排序分页，保证跨页顺序稳定。
	 */
	public Flux<Order> listAdminRedemptions(int limit, int offset) {
		return db
				.sql("SELECT " + ORDER_COLS + ORDER_SLOT_COLS + " FROM consumer_order o" + ORDER_SLOT_JOIN
						+ REDEMPTION_STATUSES_PREDICATE + " ORDER BY o.created_at DESC LIMIT :limit OFFSET :offset")
				.bind("limit", bounded(limit)).bind("offset", Math.max(0, offset))
				.map(CommerceRepository::mapOrderWithSlot).all();
	}

	/** {@link #listAdminRedemptions} 同口径 COUNT——信封 total。 */
	public Mono<Integer> countAdminRedemptions() {
		return db.sql("SELECT COUNT(*)::int AS c FROM consumer_order o" + REDEMPTION_STATUSES_PREDICATE)
				.map(row -> row.get("c", Integer.class)).one();
	}

	/** 订单下单时冻结的套餐版本规则（归因纠错的唯一金额来源；改版不影响存量订单）。 */
	public Mono<OfferVersion> findVersionRule(String versionId) {
		return db.sql("SELECT " + VERSION_COLS + " FROM commerce_package_version v WHERE v.id = CAST(:id AS uuid)")
				.bind("id", versionId).map(CommerceRepository::mapVersion).one();
	}

	/** 订单的 rlid 归因事实（source=referral_link 的最新一行；无 → empty=自然流量/纠错单）。 */
	public Mono<ReferralAttributionFact> findReferralAttribution(String orderId) {
		return db.sql("""
				SELECT recommender_account_id::text, referral_link_id, touched_at, reason
				  FROM consumer_order_attribution
				 WHERE order_id = CAST(:orderId AS uuid) AND source = 'referral_link'
				 ORDER BY effective_at DESC, created_at DESC LIMIT 1
				""").bind("orderId", orderId)
				.map(row -> new ReferralAttributionFact(row.get("recommender_account_id", String.class),
						row.get("referral_link_id", String.class), instant(row, "touched_at"),
						row.get("reason", String.class)))
				.one();
	}

	/** 治理台链接生命周期：经该 rlid 归因的订单（D98-02 生命周期查询）。 */
	public Flux<ReferralLinkService.ReferralLifecycle.LifecycleOrder> listOrdersByReferralLink(String referralLinkId) {
		return db.sql("""
				SELECT o.id::text, o.status, o.price_cents, o.recommender_amount_cents, o.created_at
				  FROM consumer_order_attribution a JOIN consumer_order o ON o.id = a.order_id
				 WHERE a.referral_link_id = :link
				 ORDER BY o.created_at DESC LIMIT 100
				""").bind("link", referralLinkId)
				.map(row -> new ReferralLinkService.ReferralLifecycle.LifecycleOrder(row.get("id", String.class),
						row.get("status", String.class), row.get("price_cents", Long.class),
						row.get("recommender_amount_cents", Long.class), instant(row, "created_at")))
				.all();
	}

	public Flux<AttributionAllocation> findAttributionAllocations(String orderId) {
		return db.sql("""
				SELECT recommender_account_id::text, share_bps, amount_cents
				  FROM consumer_order_attribution_allocation
				 WHERE order_id = CAST(:orderId AS uuid)
				 ORDER BY created_at, id
				""").bind("orderId", orderId)
				.map(row -> new AttributionAllocation(row.get("recommender_account_id", String.class),
						row.get("share_bps", Integer.class), row.get("amount_cents", Long.class)))
				.all();
	}

	/** 该单最新的申诉（消费者回显用：无 → empty）。 */
	public Mono<AttributionAppeal> findLatestAttributionAppeal(String orderId) {
		return db.sql("""
				SELECT id::text, order_id::text, consumer_account_id::text, claimed_recommender_account_id::text,
				       reason, status, resolution_note, reviewed_by::text, reviewed_at, created_at
				  FROM consumer_order_attribution_appeal
				 WHERE order_id = CAST(:orderId AS uuid)
				 ORDER BY created_at DESC, id
				""").bind("orderId", orderId).map(CommerceRepository::mapAppeal).one();
	}

	public Flux<AttributionAppeal> listAttributionAppeals(String status, int limit, int offset) {
		String predicate = status == null || status.isBlank() ? "" : " WHERE status = :status";
		GenericExecuteSpec spec = db
				.sql("""
						SELECT id::text, order_id::text, consumer_account_id::text, claimed_recommender_account_id::text,
						       reason, status, resolution_note, reviewed_by::text, reviewed_at, created_at
						  FROM consumer_order_attribution_appeal"""
						+ predicate + " ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
				.bind("limit", Math.max(1, Math.min(limit, 200))).bind("offset", Math.max(0, offset));
		if (!predicate.isEmpty())
			spec = spec.bind("status", status);
		return spec.map(CommerceRepository::mapAppeal).all();
	}

	public Mono<Integer> countAttributionAppeals(String status) {
		String predicate = status == null || status.isBlank() ? "" : " WHERE status = :status";
		GenericExecuteSpec spec = db.sql("SELECT COUNT(*)::int FROM consumer_order_attribution_appeal" + predicate);
		if (!predicate.isEmpty())
			spec = spec.bind("status", status);
		return spec.map(row -> row.get(0, Integer.class)).one();
	}

	public Mono<AfterSalesDispute> findAfterSalesDispute(String orderId) {
		return db.sql("""
				SELECT id::text, order_id::text, consumer_account_id::text, reason, status,
				       resolution, resolution_amount_cents, resolution_reason, refund_operation_id,
				       resolution_actor_account_id::text,
				       created_at, resolved_at
				  FROM consumer_order_after_sales_dispute
				 WHERE order_id = CAST(:orderId AS uuid)
				""").bind("orderId", orderId).map(row -> new AfterSalesDispute(row.get("id", String.class),
				row.get("order_id", String.class), row.get("consumer_account_id", String.class),
				row.get("reason", String.class), row.get("status", String.class), row.get("resolution", String.class),
				row.get("resolution_amount_cents", Long.class), row.get("resolution_reason", String.class),
				row.get("refund_operation_id", String.class), row.get("resolution_actor_account_id", String.class),
				instant(row, "created_at"), instant(row, "resolved_at"))).one();
	}

	/**
	 * 任务书 #75 D3：扫描状态集扩展 redeemed——冷静期已满且未完成分账的已核销单（未到期的行在 SQL 里过滤掉， 避免按 updated_at
	 * 反复空转）；redeeming 保持原样兼容升级时刻卡住的旧在途单（split_eligible_at 为 NULL， 视为立即可分账，由
	 * dispatcher 收尾）。
	 *
	 * <p>
	 * 审查修复 01：①（R03）已核销的部分退款单（partially_refunded + redeemed_at）进入净额分账队列；
	 * ②（R02/C01-E）新增 {@code splitting} 行（执行者崩在 RPC 与收尾之间由下轮重发 finance.split 幂等
	 * 收尾）；③（R07/C01-E）生效中的 held 行在 LIMIT <b>之前</b>排除——旧实现按 updated_at 取满批次后 由
	 * attemptSplit 空转返回，较旧的 held 行可永久占满批次，饿死正常支付/退款重试与分账； 解除暂扣后行自然重回本集合。 支付行也在
	 * LIMIT 前排除过期、未到重试时间、租约持有中以及待核对/成功的资金操作； 没有操作记录的历史订单仍可入队，由执行入口补登记并原子领取。
	 */
	public Flux<Order> pendingDispatch(int limit) {
		return db.sql("SELECT " + ORDER_COLS + " FROM consumer_order o" + " WHERE ((o.status = 'pending_payment'"
				+ " AND (o.payment_deadline IS NULL OR o.payment_deadline > now())"
				+ " AND NOT EXISTS (SELECT 1 FROM commerce_fund_operation f"
				+ " WHERE f.order_id = o.id AND f.operation_type = 'payment'"
				+ " AND (f.status NOT IN ('in_flight', 'failed') OR f.next_attempt_at > now()"
				+ " OR f.lease_expires_at >= now())))" + " OR o.status IN ('refund_pending', 'redeeming', 'splitting')"
				+ " OR ((o.status = 'redeemed' OR (o.status = 'partially_refunded' AND o.redeemed_at IS NOT NULL))"
				+ " AND o.split_completed_at IS NULL AND o.split_eligible_at IS NOT NULL"
				+ " AND o.split_eligible_at <= now()))" + " AND NOT EXISTS (SELECT 1 FROM ops_order_hold h"
				+ " WHERE h.order_id = o.id AND h.status = 'held')" + " ORDER BY o.updated_at LIMIT :limit")
				.bind("limit", bounded(limit)).map(CommerceRepository::mapOrder).all();
	}

	public Mono<Review> findReview(String orderId) {
		return db
				.sql("SELECT id::text, order_id::text, consumer_account_id::text, rating, comment, created_at"
						+ " FROM consumer_review WHERE order_id = CAST(:orderId AS uuid)")
				.bind("orderId", orderId).map(CommerceRepository::mapReview).one();
	}

	/**
	 * 推荐官「我的推广」（任务书 #75 卡 B6）：本人 accepted 的套餐推广任务 + 按本人归因订单聚合的漏斗。
	 * 已核销未满冷静期（split_completed_at IS NULL）计 pending_settle，已分账计 settled。
	 */
	public Flux<RecommenderPromotion> recommenderPromotions(String accountId) {
		return db
				.sql("""
						SELECT t.id::text AS task_id, t.title AS task_title, t.status AS task_status,
						       (t.promotion_ends_at IS NOT NULL AND t.promotion_ends_at <= now()) AS promotion_ended,
						       t.commerce_package_id::text AS package_id,
						       v.title AS package_title, v.price_cents, v.recommender_share_bps, v.recommender_fixed_cents,
						       COUNT(o.id) FILTER (WHERE o.status <> 'cancelled') AS order_count,
						       COUNT(o.id) FILTER (WHERE o.redeemed_at IS NOT NULL) AS redeemed_count,
						       COALESCE(SUM(o.recommender_amount_cents) FILTER (
						           WHERE o.redeemed_at IS NOT NULL AND o.split_completed_at IS NULL), 0) AS pending_settle_cents,
						       COALESCE(SUM(o.recommender_amount_cents) FILTER (
						           WHERE o.split_completed_at IS NOT NULL), 0) AS settled_cents
						FROM task_application a
						JOIN task t ON t.id = a.task_id AND t.commerce_package_id IS NOT NULL
						JOIN commerce_package p ON p.id = t.commerce_package_id
						JOIN commerce_package_version v ON v.package_id = p.id AND v.version = p.current_version
						LEFT JOIN consumer_order o
						       ON o.task_id = t.id AND o.recommender_account_id = a.recommender_account_id
						WHERE a.recommender_account_id = CAST(:account AS uuid) AND a.status = 'accepted'
						GROUP BY t.id, t.title, t.status, t.commerce_package_id, t.created_at,
						         v.title, v.price_cents, v.recommender_share_bps, v.recommender_fixed_cents
						ORDER BY t.created_at DESC
						""")
				.bind("account", accountId).map(CommerceRepository::mapRecommenderPromotion).all();
	}

	/**
	 * 商家推广统计（任务书 #75 卡 D2）：本主体全部套餐推广任务（含已终态——漏斗是经营视图）， 订单按 task_id 快照归属（任务结束后新下单
	 * task_id 为空，自然落在本任务漏斗之外）。
	 */
	public Flux<MerchantPromotion> merchantPromotions(String organizationId, String storeId) {
		// 卡 B 后任务全为门店级（推广任务亦然）——不传 storeId = 组织全量，保留 IS NULL 谓词会让
		// 商家促销面板漏掉全部新推广任务（CommercePromotionTaskIT 断言此口径）。
		String storePredicate = storeId == null || storeId.isBlank() ? "" : " AND t.store_id = CAST(:store AS uuid)\n";
		// 注意：段落间换行显式保留（text block 拼接缺分隔符会产出 "NULLGROUP" 一类语法错）。
		String sql = """
				SELECT t.id::text AS task_id, t.title AS task_title, t.status AS task_status,
				       t.commerce_package_id::text AS package_id,
				       v.title AS package_title, v.price_cents,
				       COUNT(o.id) FILTER (WHERE o.status <> 'cancelled') AS order_count,
				       COUNT(o.id) FILTER (WHERE o.redeemed_at IS NOT NULL) AS redeemed_count,
				       COALESCE(SUM(o.recommender_amount_cents) FILTER (
				           WHERE o.redeemed_at IS NOT NULL AND o.split_completed_at IS NULL), 0) AS pending_settle_cents,
				       COALESCE(SUM(o.recommender_amount_cents) FILTER (
				           WHERE o.split_completed_at IS NOT NULL), 0) AS settled_cents,
				       COUNT(o.id) FILTER (WHERE o.status IN ('refunded', 'partially_refunded')) AS refunded_count
				FROM task t
				JOIN commerce_package p ON p.id = t.commerce_package_id
				JOIN commerce_package_version v ON v.package_id = p.id AND v.version = p.current_version
				LEFT JOIN consumer_order o ON o.task_id = t.id
				WHERE t.organization_id = CAST(:org AS uuid) AND t.commerce_package_id IS NOT NULL
				"""
				+ storePredicate + """
						GROUP BY t.id, t.title, t.status, t.commerce_package_id, t.created_at, v.title, v.price_cents
						ORDER BY t.created_at DESC
						""";
		var spec = db.sql(sql).bind("org", organizationId);
		if (storeId != null && !storeId.isBlank()) {
			spec = spec.bind("store", storeId);
		}
		return spec.map(CommerceRepository::mapMerchantPromotion).all();
	}

	/**
	 * Minimal order facts for the operations dashboard; net amounts use the shared
	 * 01 allocation function.
	 */
	public Flux<DashboardOrder> dashboardOrders() {
		return db.sql("""
				SELECT price_cents, recommender_amount_cents, merchant_amount_cents, platform_fee_cents,
				       refunded_amount_cents, split_completed_at
				  FROM consumer_order
				 WHERE recommender_account_id IS NOT NULL
				   AND (redeemed_at IS NOT NULL OR split_completed_at IS NOT NULL)
				""")
				.map((row, meta) -> new DashboardOrder(row.get("price_cents", Long.class),
						row.get("recommender_amount_cents", Long.class), row.get("merchant_amount_cents", Long.class),
						row.get("platform_fee_cents", Long.class), row.get("refunded_amount_cents", Long.class),
						row.get("split_completed_at") != null))
				.all();
	}

}
