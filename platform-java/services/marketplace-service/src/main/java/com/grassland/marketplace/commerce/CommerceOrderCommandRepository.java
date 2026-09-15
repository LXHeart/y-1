package com.grassland.marketplace.commerce;

import static com.grassland.marketplace.commerce.CommerceRepository.ORDER_COLS;
import static com.grassland.marketplace.commerce.CommerceRepository.bindInstant;
import static com.grassland.marketplace.commerce.CommerceRepository.bindText;
import static com.grassland.marketplace.commerce.CommerceRepository.bindUuid;
import static com.grassland.marketplace.commerce.CommerceRepository.bounded;

import com.grassland.marketplace.commerce.CommerceModels.AttributionAppeal;
import com.grassland.marketplace.commerce.CommerceModels.Order;
import com.grassland.marketplace.commerce.CommerceModels.Review;
import com.grassland.marketplace.commerce.CommerceRepository.NewOrder;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 任务书 #103 C103-21：consumer_order 生命周期写入（支付标记/退款请求与收尾/核销直迁/分账占位与收尾/取消补偿/
 * 归因与申诉写入/售后争议写入）——自 {@link CommerceRepository} 按职责原样搬移，SQL 与守卫零变更；
 * 行塑造与绑定助手仍共享自 facade。
 */
@Component
public class CommerceOrderCommandRepository {

	private final DatabaseClient db;

	public CommerceOrderCommandRepository(DatabaseClient db) {
		this.db = db;
	}

	public Mono<Order> insertOrder(NewOrder order) {
		GenericExecuteSpec spec = db
				.sql("""
						INSERT INTO consumer_order(
						    id, consumer_account_id, organization_id, store_id, task_id, package_id,
						    package_version_id, package_version, package_title, recommender_account_id, inventory_slot_id,
						    price_cents, recommender_share_bps, platform_fee_bps, merchant_share_bps,
						    recommender_amount_cents, platform_fee_cents, merchant_amount_cents,
						    policy_version, status, redeem_code_hash, redeem_deadline, payment_deadline,
						    payment_operation_id)
						VALUES (CAST(:id AS uuid), CAST(:consumer AS uuid), CAST(:org AS uuid), CAST(:store AS uuid),
						        CAST(:task AS uuid), CAST(:packageId AS uuid), CAST(:packageVersionId AS uuid),
						        :packageVersion, :packageTitle, CAST(:recommender AS uuid), CAST(:inventorySlot AS uuid), :price,
						        :recommenderBps, :platformBps, :merchantBps, :recommenderAmount,
						        :platformAmount, :merchantAmount, :policyVersion, 'pending_payment',
						        :codeHash, :deadline, :paymentDeadline, :paymentOperationId)
						RETURNING %s
						"""
						.formatted(ORDER_COLS.replace("o.", "")))
				.bind("id", order.id()).bind("consumer", order.consumerAccountId()).bind("org", order.organizationId())
				.bind("packageId", order.packageId()).bind("packageVersionId", order.packageVersionId())
				.bind("packageVersion", order.packageVersion()).bind("packageTitle", order.packageTitle())
				.bind("price", order.priceCents()).bind("recommenderBps", order.recommenderShareBps())
				.bind("platformBps", order.platformFeeBps()).bind("merchantBps", order.merchantShareBps())
				.bind("recommenderAmount", order.recommenderAmountCents())
				.bind("platformAmount", order.platformFeeCents()).bind("merchantAmount", order.merchantAmountCents())
				.bind("policyVersion", order.policyVersion()).bind("codeHash", order.redeemCodeHash())
				.bind("deadline", order.redeemDeadline().atOffset(ZoneOffset.UTC))
				.bind("paymentDeadline", order.paymentDeadline().atOffset(ZoneOffset.UTC))
				.bind("paymentOperationId", order.paymentOperationId());
		spec = bindUuid(spec, "store", order.storeId());
		spec = bindUuid(spec, "task", order.taskId());
		spec = bindUuid(spec, "recommender", order.recommenderAccountId());
		spec = bindUuid(spec, "inventorySlot", order.inventorySlotId());
		return spec.map(CommerceRepository::mapOrder).one();
	}

	public Mono<Order> markPaid(String id, String providerRef) {
		return db
				.sql("UPDATE consumer_order o SET status = 'paid', provider_ref = :providerRef,"
						+ " paid_at = now(), last_error = NULL, version = version + 1, updated_at = now()"
						+ " WHERE o.id = CAST(:id AS uuid) AND o.status = 'pending_payment' RETURNING " + ORDER_COLS)
				.bind("id", id).bind("providerRef", providerRef).map(CommerceRepository::mapOrder).one();
	}

	public Mono<Void> recordError(String id, String status, String message) {
		return db
				.sql("UPDATE consumer_order SET last_error = :message, updated_at = now()"
						+ " WHERE id = CAST(:id AS uuid) AND status = :status")
				.bind("id", id).bind("status", status).bind("message", truncate(message)).then();
	}

	public Mono<Order> requestRefund(String id, String operationId, long amountCents, String reason) {
		GenericExecuteSpec spec = db
				.sql("UPDATE consumer_order o SET status = 'refund_pending', refund_operation_id = :operationId,"
						+ " refund_requested_amount_cents = :amount, refund_reason = COALESCE(:reason, 'consumer_request'),"
						+ " last_error = NULL, version = version + 1, updated_at = now()"
						+ " WHERE o.id = CAST(:id AS uuid) AND o.status IN ('paid', 'partially_refunded')"
						// 任务书 #103 C103-05（R02）：已结算事实烧进 SQL——Java 侧旧快照检查只是友好提示。
						+ " AND o.split_completed_at IS NULL"
						+ " AND o.refunded_amount_cents + :amount <= o.price_cents RETURNING " + ORDER_COLS)
				.bind("id", id).bind("operationId", operationId).bind("amount", amountCents);
		spec = bindText(spec, "reason", reason);
		return spec.map(CommerceRepository::mapOrder).one();
	}

	/**
	 * 到期自动退款：未核销的 paid 单到期全退；审查修复 01（R03）后未核销的部分退款单 （partially_refunded
	 * 且未核销）同样到期收口——只退<b>剩余</b>可退本金（COALESCE 已保证）。 已核销行（redeemed_at
	 * 非空）不进本扫描，其剩余资金走净额分账。
	 */
	public Flux<Order> claimExpired(int limit) {
		return db.sql("""
				WITH candidates AS (
				    SELECT id FROM consumer_order
				     WHERE (status = 'paid' OR (status = 'partially_refunded' AND redeemed_at IS NULL))
				       AND split_completed_at IS NULL
				       AND redeem_deadline <= now()
				     ORDER BY redeem_deadline FOR UPDATE SKIP LOCKED LIMIT :limit
				)
				UPDATE consumer_order o
					   SET status = 'refund_pending',
				       refund_operation_id = COALESCE(refund_operation_id, 'commerce-refund:' || o.id::text),
				       refund_reason = COALESCE(refund_reason, 'automatic_expiry'),
				       -- markRefunded 守卫 refund_requested_amount_cents 非空；到期自动退款=全额退剩余，
				       -- 缺此列会永久卡在 refund_pending（finance 幂等空转、订单状态不落）
				       refund_requested_amount_cents = COALESCE(refund_requested_amount_cents,
				           o.price_cents - o.refunded_amount_cents),
				       version = version + 1, updated_at = now()
				  FROM candidates WHERE o.id = candidates.id
				RETURNING %s
				""".formatted(ORDER_COLS)).bind("limit", bounded(limit)).map(CommerceRepository::mapOrder).all();
	}

	/**
	 * 任务书 #41（D3）：支付超时关单 claim——条件 UPDATE 守卫迁移
	 * {@code pending_payment → cancelled}（终态），原因 {@code payment_timeout} 写入
	 * last_error（D8）。 与支付成功路径（markPaid 的
	 * {@code WHERE status='pending_payment'}）由状态机单边胜出：谁先落库谁赢。
	 * {@code payment_deadline IS NOT NULL}：NULL 视为不过期（终态历史行天然免疫，V39 前无此列的语义防御）。
	 */
	public Flux<Order> claimPaymentExpired(int limit) {
		return db.sql("""
				WITH candidates AS (
				    SELECT id FROM consumer_order
				     WHERE status = 'pending_payment'
				       AND payment_deadline IS NOT NULL AND payment_deadline <= now()
				     ORDER BY payment_deadline FOR UPDATE SKIP LOCKED LIMIT :limit
				)
				UPDATE consumer_order o
				   SET status = 'cancelled', last_error = 'payment_timeout',
				       version = version + 1, updated_at = now()
				  FROM candidates WHERE o.id = candidates.id
				RETURNING %s
				""".formatted(ORDER_COLS)).bind("limit", bounded(limit)).map(CommerceRepository::mapOrder).all();
	}

	/**
	 * 消费者主动取消未支付订单（任务书 #41 尾巴）：与 {@link #claimPaymentExpired} 同款条件 UPDATE 守卫迁移
	 * {@code pending_payment → cancelled}，原因 {@code consumer_cancelled} 写入
	 * last_error。 消费者本人 + 待支付双守卫；与支付成功路径（markPaid）由状态机单边胜出。0 行 = 已不在待支付。
	 */
	public Mono<Order> claimConsumerCancelled(String orderId, String consumerAccountId) {
		return db.sql("""
				UPDATE consumer_order o
				   SET status = 'cancelled', last_error = 'consumer_cancelled',
				       version = version + 1, updated_at = now()
				 WHERE o.id = CAST(:id AS uuid)
				   AND o.consumer_account_id = CAST(:accountId AS uuid)
				   AND o.status = 'pending_payment'
				RETURNING %s
				""".formatted(ORDER_COLS)).bind("id", orderId).bind("accountId", consumerAccountId)
				.map(CommerceRepository::mapOrder).one();
	}

	public Mono<Order> markRefunded(String id) {
		return markRefunded(id, null);
	}

	/** Complete exactly the refund operation that was persisted on the order. */
	public Mono<Order> markRefunded(String id, String expectedOperationId) {
		GenericExecuteSpec spec = db.sql("UPDATE consumer_order o SET status = CASE"
				+ " WHEN o.refunded_amount_cents + o.refund_requested_amount_cents = o.price_cents"
				+ " THEN 'refunded' ELSE 'partially_refunded' END,"
				+ " refunded_amount_cents = o.refunded_amount_cents + o.refund_requested_amount_cents,"
				+ " refunded_at = CASE WHEN o.refunded_amount_cents + o.refund_requested_amount_cents = o.price_cents"
				+ " THEN now() ELSE o.refunded_at END, refund_requested_amount_cents = NULL,"
				+ " refund_operation_id = NULL, last_error = NULL, version = version + 1, updated_at = now()"
				+ " WHERE o.id = CAST(:id AS uuid) AND o.status = 'refund_pending'"
				+ " AND o.refund_requested_amount_cents IS NOT NULL"
				+ (expectedOperationId == null ? "" : " AND o.refund_operation_id = :expectedOperationId")
				+ " RETURNING " + ORDER_COLS).bind("id", id);
		if (expectedOperationId != null) {
			spec = spec.bind("expectedOperationId", expectedOperationId);
		}
		return spec.map(CommerceRepository::mapOrder).one();
	}

	/**
	 * Append the authoritative refund amount/time once; replaying an operation is a
	 * no-op.
	 */
	public Mono<Void> insertRefundFact(String orderId, String operationId, long amountCents, String source) {
		if (operationId == null || operationId.isBlank() || amountCents <= 0) {
			return Mono.empty();
		}
		return db.sql("""
				INSERT INTO consumer_order_refund(id, order_id, operation_id, amount_cents, source)
				VALUES (CAST(:id AS uuid), CAST(:orderId AS uuid), :operationId, :amount, :source)
				ON CONFLICT (operation_id) DO NOTHING
				""").bind("id", UUID.randomUUID().toString()).bind("orderId", orderId).bind("operationId", operationId)
				.bind("amount", amountCents).bind("source", source).then();
	}

	/**
	 * 任务书 #75 D3：核销直迁（paid→redeemed，跳过 redeeming 中间态）——核销码校验/过期守卫沿用
	 * {@code redeem_deadline > now()}；同事务快照 {@code split_eligible_at = 核销时刻 +
	 * 冷静期}（后续改配置不影响已核销单，与 payment_deadline 同款语义）+ 预写 split 幂等键。商家侧核销即刻成功， 分账由
	 * dispatcher 冷静期满后触发。 审查修复 01（R03）：未核销的部分退款单（partially_refunded 且 redeemed_at
	 * IS NULL）保留核销能力；已核销行不重复核销。
	 */
	public Mono<Order> markRedeemedWithCooldown(String id, String operationId, Instant splitEligibleAt) {
		return db
				.sql("UPDATE consumer_order o SET status = 'redeemed', redeemed_at = now(),"
						+ " split_operation_id = :operationId, split_eligible_at = :eligibleAt,"
						+ " last_error = NULL, version = version + 1, updated_at = now()"
						+ " WHERE o.id = CAST(:id AS uuid)"
						+ " AND (o.status = 'paid' OR (o.status = 'partially_refunded' AND o.redeemed_at IS NULL))"
						+ " AND o.redeem_deadline > now()" + " RETURNING " + ORDER_COLS)
				.bind("id", id).bind("operationId", operationId)
				.bind("eligibleAt", splitEligibleAt.atOffset(ZoneOffset.UTC)).map(CommerceRepository::mapOrder).one();
	}

	/**
	 * 审查修复 01（R02/C01-C）：分账原子占位——单行条件 UPDATE 把
	 * {@code redeemed / partially_refunded（已核销） / redeeming（存量在途）} 迁入
	 * {@code splitting}。 售后开案（openAfterSalesDispute 状态守卫）、退款请求（requestRefund
	 * 状态守卫）与人工确认暂扣 （{@code OpsOrderHoldRepository.claimHold} 的 NOT EXISTS
	 * splitting）都竞争同一状态位， 单边胜出：分账先取得权限时其余入口 409，反之本方法 0 行跳过。held 行在 claim 阶段即被排除
	 * （C01-E：不靠执行前再查询）。
	 */
	public Mono<Order> claimSplit(String id) {
		return db.sql("""
				UPDATE consumer_order o
				   SET status = 'splitting', last_error = NULL, version = version + 1, updated_at = now()
				 WHERE o.id = CAST(:id AS uuid)
				   AND o.split_completed_at IS NULL
				   AND (o.status IN ('redeemed', 'partially_refunded') AND o.redeemed_at IS NOT NULL
				        OR o.status = 'redeeming')
				   AND NOT EXISTS (SELECT 1 FROM ops_order_hold h
				                   WHERE h.order_id = o.id AND h.status = 'held')
				RETURNING %s
				""".formatted(ORDER_COLS)).bind("id", id).map(CommerceRepository::mapOrder).one();
	}

	/**
	 * 仅用于能证明未发出资金动作的防御分支（净额为零），把 splitting 归还原 resting 状态。
	 * 外部调用失败或本地结算事实提交失败不能调用此方法：须保留 splitting 并幂等恢复。
	 */
	public Mono<Order> abandonSplitClaim(String id, String error) {
		return db.sql("""
				UPDATE consumer_order o
				   SET status = CASE WHEN o.refunded_amount_cents > 0 THEN 'partially_refunded' ELSE 'redeemed' END,
				       last_error = :error, version = version + 1, updated_at = now()
				 WHERE o.id = CAST(:id AS uuid) AND o.status = 'splitting'
				RETURNING %s
				""".formatted(ORDER_COLS)).bind("id", id).bind("error", truncate(error))
				.map(CommerceRepository::mapOrder).one();
	}

	/**
	 * 任务书 #75 D3：分账完成标记（解耦后 redeemed 不再蕴含已分账，split_completed_at 是新的完成信号）。
	 * 兼容历史在途单：升级时刻卡在 redeeming 的旧行（无 split_eligible_at）由本方法一并收尾为 redeemed +
	 * split_completed。审查修复 01（R02）：守卫改为 {@code splitting}（分账占位者唯一收尾权，
	 * 售后开案无法再把状态挪走导致财务已分账而本地事实丢失）；保留 {@code redeeming} 存量行兼容。 审查修复 01（R03）：有退款史的单归还
	 * partially_refunded（履约状态与资金事实分开表达—— split_completed_at
	 * 是结算事实，refunded_amount_cents 是资金事实）。
	 */
	public Mono<Order> markSplitCompleted(String id) {
		return db.sql("UPDATE consumer_order o SET"
				+ " status = CASE WHEN o.refunded_amount_cents > 0 THEN 'partially_refunded' ELSE 'redeemed' END,"
				+ " redeemed_at = COALESCE(o.redeemed_at, now()), split_completed_at = now(),"
				+ " last_error = NULL, version = version + 1, updated_at = now()"
				+ " WHERE o.id = CAST(:id AS uuid) AND o.status IN ('splitting', 'redeeming')" + " RETURNING "
				+ ORDER_COLS).bind("id", id).map(CommerceRepository::mapOrder).one();
	}

	/**
	 * 审查修复 01（R01/C01-B）：补偿退款预写——订单行落补偿幂等键与全额（FinanceCommerceClient.refund 直接可发），守卫
	 * cancelled + 未被其他补偿键占用；收尾时由 {@link #markCancelCompensated(String)} 清空。
	 */
	public Mono<Void> prepareCancelCompensation(String id, String operationId) {
		return db.sql("""
				UPDATE consumer_order o
				   SET refund_operation_id = :operationId,
				       refund_requested_amount_cents = o.price_cents,
				       refund_reason = 'payment_cancel_compensation',
				       version = version + 1, updated_at = now()
				 WHERE o.id = CAST(:id AS uuid) AND o.status = 'cancelled'
				   AND o.refund_operation_id IS NULL
				""").bind("id", id).bind("operationId", operationId).then();
	}

	/**
	 * 审查修复 01（R01/C01-B）：取消后补偿退款收尾——订单保持 cancelled 终态，落「已完成退款的取消 结果」（不变量
	 * 1）：累计退款=原支付额、退款时间与机器可读原因（last_error），并清掉补偿预写 字段。守卫
	 * {@code status='cancelled'}：只允许补偿路径收尾，不与任何其他迁移互踩。
	 */
	public Mono<Order> markCancelCompensated(String id) {
		return db.sql("""
				UPDATE consumer_order o
				   SET refunded_amount_cents = o.price_cents, refunded_at = now(),
				       refund_requested_amount_cents = NULL, refund_operation_id = NULL,
				       last_error = 'compensated_after_capture', version = version + 1, updated_at = now()
				 WHERE o.id = CAST(:id AS uuid) AND o.status = 'cancelled'
				RETURNING %s
				""".formatted(ORDER_COLS)).bind("id", id).map(CommerceRepository::mapOrder).one();
	}

	/**
	 * 业务审查 2026-09-07 C01：归因纠错只由运营通道触达。金额<b>不在 SQL 里按比例公式重算</b>—— 由服务端按订单冻结的
	 * {@code commerce_package_version} 规则（固定额或 bps）算好传入， 任何客户端提交的比例都不再直接落库。守卫：仅
	 * paid/partially_refunded 且未完成分账可纠错。
	 */
	public Mono<Order> correctAttribution(String id, String recommenderAccountId, int recommenderBps,
			long recommenderAmountCents, int merchantBps, long merchantAmountCents) {
		return db
				.sql("UPDATE consumer_order o SET recommender_account_id = CAST(:recommender AS uuid),"
						+ " recommender_share_bps = :recommenderBps, recommender_amount_cents = :recommenderAmount,"
						+ " merchant_share_bps = :merchantBps, merchant_amount_cents = :merchantAmount,"
						+ " version = version + 1, updated_at = now()"
						+ " WHERE o.id = CAST(:id AS uuid) AND o.status IN ('paid', 'partially_refunded')"
						+ " AND o.split_completed_at IS NULL" + " RETURNING " + ORDER_COLS)
				.bind("id", id).bind("recommender", recommenderAccountId).bind("recommenderBps", recommenderBps)
				.bind("recommenderAmount", recommenderAmountCents).bind("merchantBps", merchantBps)
				.bind("merchantAmount", merchantAmountCents).map(CommerceRepository::mapOrder).one();
	}

	public Mono<Void> insertAttribution(String orderId, String recommenderAccountId, int recommenderShareBps,
			String source, String reason, String actorAccountId) {
		GenericExecuteSpec spec = db.sql("""
				INSERT INTO consumer_order_attribution(
				    id, order_id, recommender_account_id, recommender_share_bps,
				    source, reason, actor_account_id)
				VALUES (CAST(:id AS uuid), CAST(:orderId AS uuid), CAST(:recommender AS uuid),
				        :recommenderBps, :source, :reason, CAST(:actor AS uuid))
				""").bind("id", UUID.randomUUID().toString()).bind("orderId", orderId)
				.bind("recommender", recommenderAccountId).bind("recommenderBps", recommenderShareBps)
				.bind("source", source).bind("actor", actorAccountId);
		spec = bindText(spec, "reason", reason);
		return spec.then();
	}

	/**
	 * 任务书 #98 D98-02：rlid 归因事实行——下单经推广链接归因时随订单同事务落行，记录链接与触达时间 （append-only
	 * 审计，解释读模型与治理台生命周期的数据源）。source 固定 referral_link。
	 */
	public Mono<Void> insertReferralAttribution(String orderId, String recommenderAccountId, int recommenderShareBps,
			String basis, String actorAccountId, String referralLinkId, Instant touchedAt) {
		GenericExecuteSpec spec = db.sql("""
				INSERT INTO consumer_order_attribution(
				    id, order_id, recommender_account_id, recommender_share_bps,
				    source, reason, actor_account_id, referral_link_id, touched_at)
				VALUES (CAST(:id AS uuid), CAST(:orderId AS uuid), CAST(:recommender AS uuid),
				        :recommenderBps, 'referral_link', :reason, CAST(:actor AS uuid),
				        :referralLinkId, :touchedAt)
				""").bind("id", UUID.randomUUID().toString()).bind("orderId", orderId)
				.bind("recommender", recommenderAccountId).bind("recommenderBps", recommenderShareBps)
				.bind("reason", basis).bind("actor", actorAccountId).bind("referralLinkId", referralLinkId);
		spec = bindInstant(spec, "touchedAt", touchedAt);
		return spec.then();
	}

	// ---------- 业务审查 2026-09-07 C01：归因申诉（买家只申诉，运营纠错） ----------

	/** 提交申诉：一单至多一条待处理（V55 部分唯一索引，冲突 → empty 由上层转 409）。 */
	public Mono<AttributionAppeal> insertAttributionAppeal(String orderId, String consumerAccountId,
			String claimedRecommenderAccountId, String reason) {
		return db.sql("""
				INSERT INTO consumer_order_attribution_appeal(
				    id, order_id, consumer_account_id, claimed_recommender_account_id, reason)
				VALUES (CAST(:id AS uuid), CAST(:orderId AS uuid), CAST(:consumer AS uuid),
				        CAST(:claimed AS uuid), :reason)
				ON CONFLICT (order_id) WHERE status = 'open' DO NOTHING
				RETURNING id::text, order_id::text, consumer_account_id::text,
				          claimed_recommender_account_id::text, reason, status, resolution_note,
				          reviewed_by::text, reviewed_at, created_at
				""").bind("id", UUID.randomUUID().toString()).bind("orderId", orderId)
				.bind("consumer", consumerAccountId).bind("claimed", claimedRecommenderAccountId).bind("reason", reason)
				.map(CommerceRepository::mapAppeal).one();
	}

	/** 运营处置：open → applied/rejected（守卫 UPDATE，0 行 → empty 由上层转 409）。 */
	public Mono<AttributionAppeal> resolveAttributionAppeal(String appealId, String status, String note,
			String reviewedBy) {
		GenericExecuteSpec spec = db.sql("""
				UPDATE consumer_order_attribution_appeal
				   SET status = :status, resolution_note = :note, reviewed_by = CAST(:reviewedBy AS uuid),
				       reviewed_at = now()
				 WHERE id = CAST(:id AS uuid) AND status = 'open'
				RETURNING id::text, order_id::text, consumer_account_id::text,
				          claimed_recommender_account_id::text, reason, status, resolution_note,
				          reviewed_by::text, reviewed_at, created_at
				""").bind("id", appealId).bind("status", status).bind("reviewedBy", reviewedBy);
		spec = bindText(spec, "note", note);
		return spec.map(CommerceRepository::mapAppeal).one();
	}

	public Mono<Order> openAfterSalesDispute(String id, String consumerAccountId, String reason) {
		return db
				.sql("UPDATE consumer_order o SET status = 'after_sales_disputed',"
						+ " last_error = NULL, version = version + 1, updated_at = now()"
						+ " WHERE o.id = CAST(:id AS uuid) AND o.consumer_account_id = CAST(:consumer AS uuid)"
						+ " AND o.status IN ('redeemed', 'partially_refunded') RETURNING " + ORDER_COLS)
				.bind("id", id).bind("consumer", consumerAccountId).map(CommerceRepository::mapOrder).one();
	}

	public Mono<Void> insertAfterSalesDispute(String orderId, String consumerAccountId, String reason) {
		return db.sql("""
				INSERT INTO consumer_order_after_sales_dispute(id, order_id, consumer_account_id, reason)
				VALUES (CAST(:id AS uuid), CAST(:orderId AS uuid), CAST(:consumer AS uuid), :reason)
				ON CONFLICT (order_id) DO NOTHING
				""").bind("id", UUID.randomUUID().toString()).bind("orderId", orderId)
				.bind("consumer", consumerAccountId).bind("reason", reason).then();
	}

	public Mono<Order> requestDisputeRefund(String id, String operationId, long amountCents, String reason) {
		return db.sql("UPDATE consumer_order o SET status = 'refund_pending'," + " refund_operation_id = :operationId,"
				+ " refund_requested_amount_cents = :amount, refund_reason = COALESCE(:reason, 'after_sales_refund'),"
				+ " version = version + 1, updated_at = now()"
				+ " WHERE o.id = CAST(:id AS uuid) AND o.status = 'after_sales_disputed'"
				// 任务书 #103 C103-05（R02）：售后退款同守卫——分账完成后旧快照不得推动新增退款。
				+ " AND o.split_completed_at IS NULL"
				+ " AND o.refunded_amount_cents + :amount <= o.price_cents RETURNING " + ORDER_COLS).bind("id", id)
				.bind("operationId", operationId).bind("amount", amountCents).bind("reason", reason)
				.map(CommerceRepository::mapOrder).one();
	}

	/** Persist the after-sales refund intent before the external refund call. */
	public Mono<Boolean> recordAfterSalesRefundIntent(String orderId, String operationId, long amountCents,
			String reason) {
		return recordAfterSalesRefundIntent(orderId, operationId, amountCents, reason, null);
	}

	public Mono<Boolean> recordAfterSalesRefundIntent(String orderId, String operationId, long amountCents,
			String reason, String actorAccountId) {
		GenericExecuteSpec spec = db.sql("""
				UPDATE consumer_order_after_sales_dispute
				   SET resolution = 'refund', resolution_amount_cents = :amount,
				       resolution_reason = :reason, refund_operation_id = :operationId,
				       resolution_actor_account_id = CAST(:actor AS uuid)
				 WHERE order_id = CAST(:orderId AS uuid) AND status = 'open'
				""").bind("orderId", orderId).bind("operationId", operationId).bind("amount", amountCents);
		spec = bindUuid(spec, "actor", actorAccountId);
		spec = bindText(spec, "reason", reason);
		return spec.fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
	}

	/** Close only the dispute carrying the same persisted refund operation. */
	public Mono<Void> resolveAfterSalesRefund(String orderId, String operationId, String resolutionReason) {
		GenericExecuteSpec spec = db.sql("""
				UPDATE consumer_order_after_sales_dispute
				   SET status = 'resolved', resolution = 'refund',
				       resolution_reason = :reason, resolved_at = now()
				 WHERE order_id = CAST(:orderId AS uuid) AND status = 'open'
				   AND refund_operation_id = :operationId
				""").bind("orderId", orderId).bind("operationId", operationId);
		spec = bindText(spec, "reason", resolutionReason);
		return spec.then();
	}

	public Mono<Void> resolveAfterSalesDispute(String orderId, String resolution, long amountCents,
			String resolutionReason, String refundOperationId) {
		return resolveAfterSalesDispute(orderId, resolution, amountCents, resolutionReason, refundOperationId, null);
	}

	public Mono<Void> resolveAfterSalesDispute(String orderId, String resolution, long amountCents,
			String resolutionReason, String refundOperationId, String actorAccountId) {
		GenericExecuteSpec spec = db
				.sql("UPDATE consumer_order_after_sales_dispute SET status = :status, resolution = :resolution,"
						+ " resolution_amount_cents = :amount, resolution_reason = :reason,"
						+ " refund_operation_id = :refundOperationId,"
						+ " resolution_actor_account_id = CAST(:actor AS uuid), resolved_at = now()"
						+ " WHERE order_id = CAST(:orderId AS uuid) AND status = 'open'")
				.bind("orderId", orderId).bind("status", "refund".equals(resolution) ? "resolved" : "rejected")
				.bind("resolution", resolution).bind("amount", amountCents);
		spec = bindUuid(spec, "actor", actorAccountId);
		spec = bindText(spec, "reason", resolutionReason);
		spec = bindText(spec, "refundOperationId", refundOperationId);
		return spec.then();
	}

	public Mono<Order> rejectAfterSalesDispute(String id) {
		return db.sql("UPDATE consumer_order o SET status = CASE WHEN o.refunded_amount_cents > 0"
				+ " THEN 'partially_refunded' ELSE 'redeemed' END, version = version + 1, updated_at = now()"
				+ " WHERE o.id = CAST(:id AS uuid) AND o.status = 'after_sales_disputed' RETURNING " + ORDER_COLS)
				.bind("id", id).map(CommerceRepository::mapOrder).one();
	}

	public Mono<Review> insertReview(String orderId, String accountId, int rating, String comment) {
		GenericExecuteSpec spec = db.sql("""
				INSERT INTO consumer_review(id, order_id, consumer_account_id, rating, comment)
				VALUES (CAST(:id AS uuid), CAST(:orderId AS uuid), CAST(:accountId AS uuid), :rating, :comment)
				ON CONFLICT (order_id) DO NOTHING
				RETURNING id::text, order_id::text, consumer_account_id::text, rating, comment, created_at
				""").bind("id", UUID.randomUUID().toString()).bind("orderId", orderId).bind("accountId", accountId)
				.bind("rating", rating);
		spec = bindText(spec, "comment", comment);
		return spec.map(CommerceRepository::mapReview).one();
	}

	private static String truncate(String value) {
		if (value == null || value.isBlank())
			return "unknown error";
		return value.length() <= 500 ? value : value.substring(0, 500);
	}

}
