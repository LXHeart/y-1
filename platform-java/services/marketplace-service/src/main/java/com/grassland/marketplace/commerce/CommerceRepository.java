package com.grassland.marketplace.commerce;

import com.grassland.marketplace.commerce.CommerceModels.AfterSalesDispute;
import com.grassland.marketplace.commerce.CommerceModels.AttributionAppeal;
import com.grassland.marketplace.commerce.CommerceModels.Offer;
import com.grassland.marketplace.commerce.CommerceModels.OfferDetail;
import com.grassland.marketplace.commerce.CommerceModels.OfferVersion;
import com.grassland.marketplace.commerce.CommerceModels.Order;
import com.grassland.marketplace.commerce.CommerceModels.Review;
import com.grassland.marketplace.commerce.CommerceModels.InventorySlot;
import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * R2DBC persistence for the marketplace-owned commerce aggregate.
 *
 * <p>
 * 任务书 #103 C103-21：订单生命周期写入/读取已按职责搬移至 {@link CommerceOrderCommandRepository} 与
 * {@link CommerceOrderQueryRepository}，本类保留套餐/版本/库存 目录方法、公共 record
 * 与共享行塑造助手，订单方法以委托保持既有 API 不变（行为零变更）。
 */
@Component
public class CommerceRepository {

	private static final String OFFER_COLS = "p.id::text, p.organization_id::text, p.store_id::text,"
			+ " p.task_id::text, p.owner_account_id::text, p.status, p.current_version,"
			+ " p.created_at, p.updated_at, p.published_at, p.off_sale_at";
	static final String VERSION_COLS = "v.id::text AS version_id, v.package_id::text, v.version AS package_version,"
			+ " v.title, v.description, v.price_cents, v.total_stock, v.fixed_redeem_deadline,"
			+ " v.valid_days_after_purchase, v.recommender_share_bps, v.platform_fee_bps,"
			+ " v.merchant_share_bps, v.policy_version, v.created_by::text, v.created_at AS version_created_at,"
			+ " v.recommender_fixed_cents";
	static final String ORDER_COLS = "o.id::text, o.consumer_account_id::text, o.organization_id::text,"
			+ " o.store_id::text, o.task_id::text, o.package_id::text, o.package_version_id::text,"
			+ " o.package_version, o.package_title, o.recommender_account_id::text, o.price_cents,"
			+ " o.recommender_share_bps, o.platform_fee_bps, o.merchant_share_bps,"
			+ " o.recommender_amount_cents, o.platform_fee_cents, o.merchant_amount_cents,"
			+ " o.policy_version, o.status, o.refunded_amount_cents, o.refund_requested_amount_cents,"
			+ " o.refund_reason, o.inventory_slot_id::text, o.redeem_code_hash, o.redeem_deadline,"
			+ " o.payment_deadline,"
			+ " o.payment_operation_id, o.refund_operation_id, o.split_operation_id, o.provider_ref,"
			+ " o.last_error, o.version, o.created_at, o.paid_at, o.redeemed_at, o.refunded_at, o.updated_at,"
			+ " o.split_eligible_at, o.split_completed_at";
	private final DatabaseClient db;
	private final CommerceOrderCommandRepository orderCommands;
	private final CommerceOrderQueryRepository orderQueries;

	public CommerceRepository(DatabaseClient db, CommerceOrderCommandRepository orderCommands,
			CommerceOrderQueryRepository orderQueries) {
		this.db = db;
		this.orderCommands = orderCommands;
		this.orderQueries = orderQueries;
	}

	public Mono<Offer> insertOffer(String id, String ownerAccountId, String organizationId, String storeId,
			String taskId) {
		GenericExecuteSpec spec = db.sql("""
				INSERT INTO commerce_package(
				    id, organization_id, store_id, task_id, owner_account_id, status, current_version)
				VALUES (CAST(:id AS uuid), CAST(:org AS uuid), CAST(:store AS uuid), CAST(:task AS uuid),
				        CAST(:owner AS uuid), 'draft', 1)
				RETURNING id::text, organization_id::text, store_id::text, task_id::text,
				          owner_account_id::text, status, current_version, created_at, updated_at,
				          published_at, off_sale_at
				""").bind("id", id).bind("org", organizationId).bind("owner", ownerAccountId);
		spec = bindUuid(spec, "store", storeId);
		spec = bindUuid(spec, "task", taskId);
		return spec.map(CommerceRepository::mapOfferUnaliased).one();
	}

	public Mono<OfferVersion> insertVersion(String id, String packageId, int version, OfferInput input,
			String createdBy) {
		GenericExecuteSpec spec = db.sql("""
				INSERT INTO commerce_package_version(
				    id, package_id, version, title, description, price_cents, total_stock,
				    fixed_redeem_deadline, valid_days_after_purchase, recommender_share_bps,
				    platform_fee_bps, merchant_share_bps, policy_version, created_by, recommender_fixed_cents)
				VALUES (CAST(:id AS uuid), CAST(:packageId AS uuid), :version, :title, :description,
				        :price, :stock, :fixedDeadline, :validDays, :recommenderBps, :platformBps,
				        :merchantBps, :policyVersion, CAST(:createdBy AS uuid), :fixedCents)
				RETURNING id::text AS version_id, package_id::text, version AS package_version,
				          title, description, price_cents, total_stock, fixed_redeem_deadline,
				          valid_days_after_purchase, recommender_share_bps, platform_fee_bps,
				          merchant_share_bps, policy_version, created_by::text,
				          created_at AS version_created_at, recommender_fixed_cents
				""").bind("id", id).bind("packageId", packageId).bind("version", version).bind("title", input.title())
				.bind("price", input.priceCents()).bind("stock", input.totalStock())
				.bind("recommenderBps", input.recommenderShareBps()).bind("platformBps", input.platformFeeBps())
				.bind("merchantBps", input.merchantShareBps()).bind("policyVersion", input.policyVersion())
				.bind("createdBy", createdBy);
		spec = bindText(spec, "description", input.description());
		spec = bindInstant(spec, "fixedDeadline", input.fixedRedeemDeadline());
		spec = input.validDaysAfterPurchase() == null
				? spec.bindNull("validDays", Integer.class)
				: spec.bind("validDays", input.validDaysAfterPurchase());
		spec = input.recommenderFixedCents() == null
				? spec.bindNull("fixedCents", Integer.class)
				: spec.bind("fixedCents", input.recommenderFixedCents().intValue());
		return spec.map(CommerceRepository::mapVersion).one();
	}

	public Mono<Void> insertInventory(String versionId, int totalStock) {
		return db.sql("""
				INSERT INTO commerce_package_inventory(package_version_id, total_stock, remaining_stock)
				VALUES (CAST(:versionId AS uuid), :stock, :stock)
				""").bind("versionId", versionId).bind("stock", totalStock).then();
	}

	public Mono<Void> insertInventorySlots(String versionId, java.util.List<InventorySlotInput> slots) {
		if (slots == null || slots.isEmpty())
			return Mono.empty();
		return Flux.fromIterable(slots).flatMap(slot -> {
			GenericExecuteSpec spec = db.sql("""
					INSERT INTO commerce_package_inventory_slot(
					    id, package_version_id, store_id, slot_start, slot_end, total_stock, remaining_stock)
					VALUES (CAST(:id AS uuid), CAST(:versionId AS uuid), CAST(:store AS uuid),
					        :slotStart, :slotEnd, :stock, :stock)
					""").bind("id", UUID.randomUUID().toString()).bind("versionId", versionId)
					.bind("slotStart", slot.slotStart().atOffset(ZoneOffset.UTC))
					.bind("slotEnd", slot.slotEnd().atOffset(ZoneOffset.UTC)).bind("stock", slot.totalStock());
			spec = bindUuid(spec, "store", slot.storeId());
			return spec.then();
		}).then();
	}

	public Flux<InventorySlot> slots(String versionId) {
		return db.sql("""
				SELECT id::text, package_version_id::text, store_id::text, slot_start, slot_end,
				       total_stock, remaining_stock
				  FROM commerce_package_inventory_slot
				 WHERE package_version_id = CAST(:versionId AS uuid) ORDER BY slot_start
				""").bind("versionId", versionId)
				.map(row -> new InventorySlot(row.get("id", String.class), row.get("package_version_id", String.class),
						row.get("store_id", String.class), instant(row, "slot_start"), instant(row, "slot_end"),
						row.get("total_stock", Integer.class), row.get("remaining_stock", Integer.class)))
				.all();
	}

	public Mono<Offer> setCurrentVersion(String packageId, int expectedVersion, int nextVersion) {
		return db.sql("""
				UPDATE commerce_package
				   SET current_version = :nextVersion, updated_at = now()
				 WHERE id = CAST(:id AS uuid) AND current_version = :expectedVersion
				RETURNING id::text, organization_id::text, store_id::text, task_id::text,
				          owner_account_id::text, status, current_version, created_at, updated_at,
				          published_at, off_sale_at
				""").bind("id", packageId).bind("expectedVersion", expectedVersion).bind("nextVersion", nextVersion)
				.map(CommerceRepository::mapOfferUnaliased).one();
	}

	public Mono<Offer> findOffer(String id) {
		return db.sql("SELECT " + OFFER_COLS + " FROM commerce_package p WHERE p.id = CAST(:id AS uuid)").bind("id", id)
				.map(CommerceRepository::mapOffer).one();
	}

	public Mono<OfferDetail> findDetail(String id) {
		return db
				.sql("SELECT " + OFFER_COLS + ", " + VERSION_COLS + ", i.remaining_stock FROM commerce_package p"
						+ " JOIN commerce_package_version v ON v.package_id = p.id AND v.version = p.current_version"
						+ " JOIN commerce_package_inventory i ON i.package_version_id = v.id"
						+ " WHERE p.id = CAST(:id AS uuid)")
				.bind("id", id).map(CommerceRepository::mapDetail).one()
				.flatMap(detail -> slots(detail.version().id()).collectList().map(
						values -> new OfferDetail(detail.offer(), detail.version(), detail.remainingStock(), values)));
	}

	public Flux<OfferDetail> listOffers(String organizationId, String storeId) {
		String storePredicate = storeId == null || storeId.isBlank()
				? "p.store_id IS NULL"
				: "p.store_id = CAST(:store AS uuid)";
		GenericExecuteSpec spec = db.sql("SELECT " + OFFER_COLS + ", " + VERSION_COLS
				+ ", i.remaining_stock FROM commerce_package p"
				+ " JOIN commerce_package_version v ON v.package_id = p.id AND v.version = p.current_version"
				+ " JOIN commerce_package_inventory i ON i.package_version_id = v.id"
				+ " WHERE p.organization_id = CAST(:org AS uuid) AND " + storePredicate + " ORDER BY p.updated_at DESC")
				.bind("org", organizationId);
		if (storeId != null && !storeId.isBlank())
			spec = spec.bind("store", storeId);
		return spec.map(CommerceRepository::mapDetail).all()
				.flatMap(detail -> slots(detail.version().id()).collectList().map(
						values -> new OfferDetail(detail.offer(), detail.version(), detail.remainingStock(), values)));
	}

	public Mono<Offer> publish(String id) {
		return transitionOffer(id, "published");
	}

	public Mono<Offer> offSale(String id) {
		return transitionOffer(id, "off_sale");
	}

	private Mono<Offer> transitionOffer(String id, String status) {
		String timestamps = "published".equals(status)
				? "published_at = COALESCE(published_at, now()), off_sale_at = NULL"
				: "off_sale_at = now()";
		return db
				.sql("UPDATE commerce_package SET status = :status, " + timestamps
						+ ", updated_at = now() WHERE id = CAST(:id AS uuid) RETURNING "
						+ "id::text, organization_id::text, store_id::text, task_id::text,"
						+ " owner_account_id::text, status, current_version, created_at, updated_at,"
						+ " published_at, off_sale_at")
				.bind("id", id).bind("status", status).map(CommerceRepository::mapOfferUnaliased).one();
	}

	public Mono<Integer> reserveInventory(String versionId) {
		return db.sql("""
				UPDATE commerce_package_inventory
				   SET remaining_stock = remaining_stock - 1, updated_at = now()
				 WHERE package_version_id = CAST(:versionId AS uuid) AND remaining_stock > 0
				RETURNING remaining_stock
				""").bind("versionId", versionId).map(row -> row.get("remaining_stock", Integer.class)).one();
	}

	public Mono<Integer> reserveInventory(String versionId, String slotId) {
		if (slotId == null || slotId.isBlank())
			return reserveInventory(versionId);
		return db.sql("""
				UPDATE commerce_package_inventory_slot SET remaining_stock = remaining_stock - 1, updated_at = now()
				 WHERE id = CAST(:slotId AS uuid) AND package_version_id = CAST(:versionId AS uuid)
				   AND remaining_stock > 0 RETURNING remaining_stock
				""").bind("slotId", slotId).bind("versionId", versionId)
				.map(row -> row.get("remaining_stock", Integer.class)).one();
	}

	/**
	 * 任务书 #41（D5）：关单释放库存——与 {@link #reserveInventory(String)} 完全对称的 UPDATE。
	 * {@code remaining_stock < total_stock} 守卫封顶：重复释放（双重关单/补偿重放）返回 0 行被静默吸收，
	 * 不会把库存刷爆到 total 之上。释放成功返回回升后的 remaining_stock。
	 */
	public Mono<Integer> releaseInventory(String versionId) {
		return db.sql("""
				UPDATE commerce_package_inventory
				   SET remaining_stock = remaining_stock + 1, updated_at = now()
				 WHERE package_version_id = CAST(:versionId AS uuid) AND remaining_stock < total_stock
				RETURNING remaining_stock
				""").bind("versionId", versionId).map(row -> row.get("remaining_stock", Integer.class)).one();
	}

	/** slot 级释放（D5/D6）：按订单快照的 slotId 精确归还，不按当前套餐版本猜。 */
	public Mono<Integer> releaseInventory(String versionId, String slotId) {
		if (slotId == null || slotId.isBlank())
			return releaseInventory(versionId);
		return db.sql("""
				UPDATE commerce_package_inventory_slot SET remaining_stock = remaining_stock + 1, updated_at = now()
				 WHERE id = CAST(:slotId AS uuid) AND package_version_id = CAST(:versionId AS uuid)
				   AND remaining_stock < total_stock RETURNING remaining_stock
				""").bind("slotId", slotId).bind("versionId", versionId)
				.map(row -> row.get("remaining_stock", Integer.class)).one();
	}

	public Mono<Void> replenishInventory(String versionId) {
		return db.sql("""
				UPDATE commerce_package_inventory
				   SET remaining_stock = LEAST(total_stock, remaining_stock + 1), updated_at = now()
				 WHERE package_version_id = CAST(:versionId AS uuid)
				""").bind("versionId", versionId).then();
	}

	public Mono<Void> replenishInventory(String versionId, String slotId) {
		if (slotId == null || slotId.isBlank())
			return replenishInventory(versionId);
		return db.sql("""
				UPDATE commerce_package_inventory_slot
				   SET remaining_stock = LEAST(total_stock, remaining_stock + 1), updated_at = now()
				 WHERE id = CAST(:slotId AS uuid) AND package_version_id = CAST(:versionId AS uuid)
				""").bind("slotId", slotId).bind("versionId", versionId).then();
	}
	// ---------- 任务书 #103 C103-21：订单命令/查询已按职责搬移，facade 委托保持公共 API 不变 ----------

	public Mono<Order> insertOrder(NewOrder order) {
		return orderCommands.insertOrder(order);
	}

	public Mono<Order> findOrder(String id) {
		return orderQueries.findOrder(id);
	}

	public Mono<Order> findOrderByCodeHash(String hash) {
		return orderQueries.findOrderByCodeHash(hash);
	}

	public Flux<Order> listConsumerOrders(String accountId, int limit) {
		return orderQueries.listConsumerOrders(accountId, limit);
	}

	public Flux<Order> listMerchantOrders(String organizationId, String storeId, int limit) {
		return orderQueries.listMerchantOrders(organizationId, storeId, limit);
	}

	public Flux<Order> exportMerchantOrders(String organizationId, String storeId, String status, Instant from,
			Instant to, int limit) {
		return orderQueries.exportMerchantOrders(organizationId, storeId, status, from, to, limit);
	}

	public Flux<Order> listAdminOrders(String status, int limit, int offset) {
		return orderQueries.listAdminOrders(status, limit, offset);
	}

	public Mono<Integer> countAdminOrders(String status) {
		return orderQueries.countAdminOrders(status);
	}

	public Flux<Order> listAdminRedemptions(int limit, int offset) {
		return orderQueries.listAdminRedemptions(limit, offset);
	}

	public Mono<Integer> countAdminRedemptions() {
		return orderQueries.countAdminRedemptions();
	}

	public Mono<Order> markPaid(String id, String providerRef) {
		return orderCommands.markPaid(id, providerRef);
	}

	public Mono<Void> recordError(String id, String status, String message) {
		return orderCommands.recordError(id, status, message);
	}

	public Mono<Order> requestRefund(String id, String operationId, long amountCents, String reason) {
		return orderCommands.requestRefund(id, operationId, amountCents, reason);
	}

	public Flux<Order> claimExpired(int limit) {
		return orderCommands.claimExpired(limit);
	}

	public Flux<Order> claimPaymentExpired(int limit) {
		return orderCommands.claimPaymentExpired(limit);
	}

	public Mono<Order> claimConsumerCancelled(String orderId, String consumerAccountId) {
		return orderCommands.claimConsumerCancelled(orderId, consumerAccountId);
	}

	public Mono<Order> markRefunded(String id) {
		return orderCommands.markRefunded(id);
	}

	public Mono<Order> markRefunded(String id, String expectedOperationId) {
		return orderCommands.markRefunded(id, expectedOperationId);
	}

	public Mono<Void> insertRefundFact(String orderId, String operationId, long amountCents, String source) {
		return orderCommands.insertRefundFact(orderId, operationId, amountCents, source);
	}

	public Mono<Order> markRedeemedWithCooldown(String id, String operationId, Instant splitEligibleAt) {
		return orderCommands.markRedeemedWithCooldown(id, operationId, splitEligibleAt);
	}

	public Mono<Order> claimSplit(String id) {
		return orderCommands.claimSplit(id);
	}

	public Mono<Order> abandonSplitClaim(String id, String error) {
		return orderCommands.abandonSplitClaim(id, error);
	}

	public Mono<Order> markSplitCompleted(String id) {
		return orderCommands.markSplitCompleted(id);
	}

	public Mono<Void> prepareCancelCompensation(String id, String operationId) {
		return orderCommands.prepareCancelCompensation(id, operationId);
	}

	public Mono<Order> markCancelCompensated(String id) {
		return orderCommands.markCancelCompensated(id);
	}

	public Mono<Order> correctAttribution(String id, String recommenderAccountId, int recommenderBps,
			long recommenderAmountCents, int merchantBps, long merchantAmountCents) {
		return orderCommands.correctAttribution(id, recommenderAccountId, recommenderBps, recommenderAmountCents,
				merchantBps, merchantAmountCents);
	}

	public Mono<OfferVersion> findVersionRule(String versionId) {
		return orderQueries.findVersionRule(versionId);
	}

	public Mono<Void> insertAttribution(String orderId, String recommenderAccountId, int recommenderShareBps,
			String source, String reason, String actorAccountId) {
		return orderCommands.insertAttribution(orderId, recommenderAccountId, recommenderShareBps, source, reason,
				actorAccountId);
	}

	public Mono<Void> insertReferralAttribution(String orderId, String recommenderAccountId, int recommenderShareBps,
			String basis, String actorAccountId, String referralLinkId, Instant touchedAt) {
		return orderCommands.insertReferralAttribution(orderId, recommenderAccountId, recommenderShareBps, basis,
				actorAccountId, referralLinkId, touchedAt);
	}

	public Mono<ReferralAttributionFact> findReferralAttribution(String orderId) {
		return orderQueries.findReferralAttribution(orderId);
	}

	public Flux<ReferralLinkService.ReferralLifecycle.LifecycleOrder> listOrdersByReferralLink(String referralLinkId) {
		return orderQueries.listOrdersByReferralLink(referralLinkId);
	}

	public Flux<AttributionAllocation> findAttributionAllocations(String orderId) {
		return orderQueries.findAttributionAllocations(orderId);
	}

	public Mono<AttributionAppeal> insertAttributionAppeal(String orderId, String consumerAccountId,
			String claimedRecommenderAccountId, String reason) {
		return orderCommands.insertAttributionAppeal(orderId, consumerAccountId, claimedRecommenderAccountId, reason);
	}

	public Mono<AttributionAppeal> findLatestAttributionAppeal(String orderId) {
		return orderQueries.findLatestAttributionAppeal(orderId);
	}

	public Mono<AttributionAppeal> resolveAttributionAppeal(String appealId, String status, String note,
			String reviewedBy) {
		return orderCommands.resolveAttributionAppeal(appealId, status, note, reviewedBy);
	}

	public Flux<AttributionAppeal> listAttributionAppeals(String status, int limit, int offset) {
		return orderQueries.listAttributionAppeals(status, limit, offset);
	}

	public Mono<Integer> countAttributionAppeals(String status) {
		return orderQueries.countAttributionAppeals(status);
	}

	public Mono<Order> openAfterSalesDispute(String id, String consumerAccountId, String reason) {
		return orderCommands.openAfterSalesDispute(id, consumerAccountId, reason);
	}

	public Mono<Void> insertAfterSalesDispute(String orderId, String consumerAccountId, String reason) {
		return orderCommands.insertAfterSalesDispute(orderId, consumerAccountId, reason);
	}

	public Mono<AfterSalesDispute> findAfterSalesDispute(String orderId) {
		return orderQueries.findAfterSalesDispute(orderId);
	}

	public Mono<Order> requestDisputeRefund(String id, String operationId, long amountCents, String reason) {
		return orderCommands.requestDisputeRefund(id, operationId, amountCents, reason);
	}

	public Mono<Boolean> recordAfterSalesRefundIntent(String orderId, String operationId, long amountCents,
			String reason) {
		return orderCommands.recordAfterSalesRefundIntent(orderId, operationId, amountCents, reason);
	}

	public Mono<Boolean> recordAfterSalesRefundIntent(String orderId, String operationId, long amountCents,
			String reason, String actorAccountId) {
		return orderCommands.recordAfterSalesRefundIntent(orderId, operationId, amountCents, reason, actorAccountId);
	}

	public Mono<Void> resolveAfterSalesRefund(String orderId, String operationId, String resolutionReason) {
		return orderCommands.resolveAfterSalesRefund(orderId, operationId, resolutionReason);
	}

	public Mono<Void> resolveAfterSalesDispute(String orderId, String resolution, long amountCents,
			String resolutionReason, String refundOperationId) {
		return orderCommands.resolveAfterSalesDispute(orderId, resolution, amountCents, resolutionReason,
				refundOperationId);
	}

	public Mono<Void> resolveAfterSalesDispute(String orderId, String resolution, long amountCents,
			String resolutionReason, String refundOperationId, String actorAccountId) {
		return orderCommands.resolveAfterSalesDispute(orderId, resolution, amountCents, resolutionReason,
				refundOperationId, actorAccountId);
	}

	public Mono<Order> rejectAfterSalesDispute(String id) {
		return orderCommands.rejectAfterSalesDispute(id);
	}

	public Flux<Order> pendingDispatch(int limit) {
		return orderQueries.pendingDispatch(limit);
	}

	public Mono<Review> insertReview(String orderId, String accountId, int rating, String comment) {
		return orderCommands.insertReview(orderId, accountId, rating, comment);
	}

	public Mono<Review> findReview(String orderId) {
		return orderQueries.findReview(orderId);
	}

	public Flux<RecommenderPromotion> recommenderPromotions(String accountId) {
		return orderQueries.recommenderPromotions(accountId);
	}

	public Flux<MerchantPromotion> merchantPromotions(String organizationId, String storeId) {
		return orderQueries.merchantPromotions(organizationId, storeId);
	}

	public Flux<DashboardOrder> dashboardOrders() {
		return orderQueries.dashboardOrders();
	}

	public record ReferralAttributionFact(String recommenderAccountId, String referralLinkId, Instant touchedAt,
			String reason) {
	}

	static AttributionAppeal mapAppeal(Readable row) {
		return new AttributionAppeal(row.get("id", String.class), row.get("order_id", String.class),
				row.get("consumer_account_id", String.class), row.get("claimed_recommender_account_id", String.class),
				row.get("reason", String.class), row.get("status", String.class),
				row.get("resolution_note", String.class), row.get("reviewed_by", String.class),
				instant(row, "reviewed_at"), instant(row, "created_at"));
	}
	// 任务书 #75 D5：replaceAttributionAllocations 已删——V37 表冻结增量（存量行仅供历史 redeeming 单
	// 分账与冲销读取），createOrder/rebindAttribution 均不再写入。
	private static OfferDetail mapDetail(Readable row) {
		return new OfferDetail(mapOffer(row), mapVersion(row), row.get("remaining_stock", Integer.class),
				java.util.List.<InventorySlot>of());
	}

	private static Offer mapOffer(Readable row) {
		return new Offer(row.get("id", String.class), row.get("organization_id", String.class),
				row.get("store_id", String.class), row.get("task_id", String.class),
				row.get("owner_account_id", String.class), row.get("status", String.class),
				row.get("current_version", Integer.class), instant(row, "created_at"), instant(row, "updated_at"),
				instant(row, "published_at"), instant(row, "off_sale_at"));
	}

	private static Offer mapOfferUnaliased(Readable row) {
		return mapOffer(row);
	}

	static OfferVersion mapVersion(Readable row) {
		return new OfferVersion(row.get("version_id", String.class), row.get("package_id", String.class),
				row.get("package_version", Integer.class), row.get("title", String.class),
				row.get("description", String.class), row.get("price_cents", Long.class),
				row.get("total_stock", Integer.class), instant(row, "fixed_redeem_deadline"),
				row.get("valid_days_after_purchase", Integer.class), row.get("recommender_share_bps", Integer.class),
				row.get("platform_fee_bps", Integer.class), row.get("merchant_share_bps", Integer.class),
				row.get("policy_version", String.class), row.get("created_by", String.class),
				instant(row, "version_created_at"),
				row.get("recommender_fixed_cents", Integer.class) == null
						? null
						: row.get("recommender_fixed_cents", Integer.class).longValue());
	}

	static Order mapOrder(Readable row) {
		return order(row, null, null);
	}

	static Order mapOrderWithSlot(Readable row) {
		return order(row, instant(row, "slot_start"), instant(row, "slot_end"));
	}

	private static Order order(Readable row, Instant slotStart, Instant slotEnd) {
		return new Order(row.get("id", String.class), row.get("consumer_account_id", String.class),
				row.get("organization_id", String.class), row.get("store_id", String.class),
				row.get("task_id", String.class), row.get("package_id", String.class),
				row.get("package_version_id", String.class), row.get("package_version", Integer.class),
				row.get("package_title", String.class), row.get("recommender_account_id", String.class),
				row.get("price_cents", Long.class), row.get("recommender_share_bps", Integer.class),
				row.get("platform_fee_bps", Integer.class), row.get("merchant_share_bps", Integer.class),
				row.get("recommender_amount_cents", Long.class), row.get("platform_fee_cents", Long.class),
				row.get("merchant_amount_cents", Long.class), row.get("policy_version", String.class),
				row.get("status", String.class), row.get("refunded_amount_cents", Long.class),
				row.get("refund_requested_amount_cents", Long.class), row.get("refund_reason", String.class),
				row.get("inventory_slot_id", String.class), row.get("redeem_code_hash", String.class),
				instant(row, "redeem_deadline"), instant(row, "payment_deadline"),
				row.get("payment_operation_id", String.class), row.get("refund_operation_id", String.class),
				row.get("split_operation_id", String.class), row.get("provider_ref", String.class),
				row.get("last_error", String.class), row.get("version", Integer.class), instant(row, "created_at"),
				instant(row, "paid_at"), instant(row, "redeemed_at"), instant(row, "refunded_at"),
				instant(row, "updated_at"), slotStart, slotEnd, instant(row, "split_eligible_at"),
				instant(row, "split_completed_at"));
	}

	static Review mapReview(Readable row) {
		return new Review(row.get("id", String.class), row.get("order_id", String.class),
				row.get("consumer_account_id", String.class), row.get("rating", Integer.class),
				row.get("comment", String.class), instant(row, "created_at"));
	}

	static Instant instant(Readable row, String name) {
		OffsetDateTime value = row.get(name, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	static int bounded(int limit) {
		return Math.max(1, Math.min(limit, 200));
	}
	static GenericExecuteSpec bindUuid(GenericExecuteSpec spec, String name, String value) {
		return value == null || value.isBlank() ? spec.bindNull(name, String.class) : spec.bind(name, value);
	}
	static GenericExecuteSpec bindText(GenericExecuteSpec spec, String name, String value) {
		return value == null || value.isBlank() ? spec.bindNull(name, String.class) : spec.bind(name, value);
	}
	static GenericExecuteSpec bindInstant(GenericExecuteSpec spec, String name, Instant value) {
		return value == null
				? spec.bindNull(name, OffsetDateTime.class)
				: spec.bind(name, value.atOffset(ZoneOffset.UTC));
	}

	public record OfferInput(String title, String description, long priceCents, int totalStock,
			Instant fixedRedeemDeadline, Integer validDaysAfterPurchase, int recommenderShareBps, int platformFeeBps,
			int merchantShareBps, String policyVersion, java.util.List<InventorySlotInput> inventorySlots,
			Long recommenderFixedCents) {

		/** 便捷构造：任务书 #75 之前的签名（固定佣 null）。 */
		public OfferInput(String title, String description, long priceCents, int totalStock,
				Instant fixedRedeemDeadline, Integer validDaysAfterPurchase, int recommenderShareBps,
				int platformFeeBps, int merchantShareBps, String policyVersion,
				java.util.List<InventorySlotInput> inventorySlots) {
			this(title, description, priceCents, totalStock, fixedRedeemDeadline, validDaysAfterPurchase,
					recommenderShareBps, platformFeeBps, merchantShareBps, policyVersion, inventorySlots, null);
		}
	}

	public record InventorySlotInput(String storeId, Instant slotStart, Instant slotEnd, int totalStock) {
	}

	public record AttributionAllocation(String recommenderAccountId, int shareBps, long amountCents) {
	}

	public record NewOrder(String id, String consumerAccountId, String organizationId, String storeId, String taskId,
			String packageId, String packageVersionId, int packageVersion, String packageTitle,
			String recommenderAccountId, long priceCents, int recommenderShareBps, int platformFeeBps,
			int merchantShareBps, long recommenderAmountCents, long platformFeeCents, long merchantAmountCents,
			String policyVersion, String redeemCodeHash, Instant redeemDeadline, Instant paymentDeadline,
			String paymentOperationId, String inventorySlotId) {
	}

	// ---------- 任务书 #75：任务-套餐关联回填与推广统计 ----------

	/** 套餐推广任务创建成功后回填 commerce_package.task_id（占用标记，任务终态时清空）。 */
	public Mono<Void> linkPromotionTask(String packageId, String taskId) {
		return db.sql("UPDATE commerce_package SET task_id = CAST(:task AS uuid), updated_at = now()"
				+ " WHERE id = CAST(:pkg AS uuid)").bind("pkg", packageId).bind("task", taskId).then();
	}

	/** 任务终态（截止/关闭/取消/下架联动）清空回填——「进行中任务才占用」。 */
	public Mono<Void> unlinkPromotionTaskByTask(String taskId) {
		return db.sql("UPDATE commerce_package SET task_id = NULL, updated_at = now()"
				+ " WHERE task_id = CAST(:task AS uuid)").bind("task", taskId).then();
	}

	/** 套餐推广摘要（任务视图增强用）：当前版本标题/价格/佣金形态；查不到的 id 不进 map。 */
	public Mono<java.util.Map<String, PromotionSummary>> findPromotionSummaries(java.util.List<String> packageIds) {
		if (packageIds == null || packageIds.isEmpty()) {
			return Mono.just(java.util.Map.of());
		}
		return db.sql("SELECT p.id::text AS package_id, v.title, v.price_cents, v.recommender_share_bps,"
				+ " v.recommender_fixed_cents, p.status AS package_status" + " FROM commerce_package p"
				+ " JOIN commerce_package_version v ON v.package_id = p.id AND v.version = p.current_version"
				// uuid 列与 text 绑定比较需显式转型（照 TaskRepository.findFeed 的 store_id::text IN 先例）。
				+ " WHERE p.id::text IN (:ids)").bind("ids", packageIds)
				.map(row -> new PromotionSummary(row.get("package_id", String.class), row.get("title", String.class),
						row.get("price_cents", Long.class), row.get("recommender_share_bps", Integer.class),
						row.get("recommender_fixed_cents", Integer.class) == null
								? null
								: row.get("recommender_fixed_cents", Integer.class).longValue(),
						row.get("package_status", String.class)))
				.all().collectMap(PromotionSummary::packageId);
	}

	static RecommenderPromotion mapRecommenderPromotion(Readable row) {
		return new RecommenderPromotion(row.get("task_id", String.class), row.get("task_title", String.class),
				row.get("task_status", String.class), row.get("package_id", String.class),
				row.get("package_title", String.class), row.get("price_cents", Long.class),
				row.get("recommender_share_bps", Integer.class),
				row.get("recommender_fixed_cents", Integer.class) == null
						? null
						: row.get("recommender_fixed_cents", Integer.class).longValue(),
				((Number) row.get("order_count", Long.class)).intValue(),
				((Number) row.get("redeemed_count", Long.class)).intValue(),
				row.get("pending_settle_cents", Long.class), row.get("settled_cents", Long.class),
				Boolean.TRUE.equals(row.get("promotion_ended", Boolean.class)));
	}

	static MerchantPromotion mapMerchantPromotion(Readable row) {
		return new MerchantPromotion(row.get("task_id", String.class), row.get("task_title", String.class),
				row.get("task_status", String.class), row.get("package_id", String.class),
				row.get("package_title", String.class), row.get("price_cents", Long.class),
				((Number) row.get("order_count", Long.class)).intValue(),
				((Number) row.get("redeemed_count", Long.class)).intValue(),
				row.get("pending_settle_cents", Long.class), row.get("settled_cents", Long.class),
				((Number) row.get("refunded_count", Long.class)).intValue());
	}
	public record PromotionSummary(String packageId, String title, Long priceCents, Integer recommenderShareBps,
			Long recommenderFixedCents, String packageStatus) {
	}

	public record RecommenderPromotion(String taskId, String taskTitle, String taskStatus, String packageId,
			String packageTitle, long priceCents, Integer recommenderShareBps, Long recommenderFixedCents,
			int orderCount, int redeemedCount, long pendingSettleCents, long settledCents, boolean promotionEnded) {
	}

	public record MerchantPromotion(String taskId, String taskTitle, String taskStatus, String packageId,
			String packageTitle, long priceCents, int orderCount, int redeemedCount, long pendingSettleCents,
			long settledCents, int refundedCount) {
	}
	public record DashboardOrder(long priceCents, long recommenderAmountCents, long merchantAmountCents,
			long platformFeeCents, long refundedAmountCents, boolean settled) {
	}
}