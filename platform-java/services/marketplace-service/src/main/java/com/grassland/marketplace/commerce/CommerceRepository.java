package com.grassland.marketplace.commerce;

import com.grassland.marketplace.commerce.CommerceModels.AfterSalesDispute;
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

/** R2DBC persistence for the marketplace-owned commerce aggregate. */
@Component
public class CommerceRepository {

	private static final String OFFER_COLS = "p.id::text, p.organization_id::text, p.store_id::text,"
			+ " p.task_id::text, p.owner_account_id::text, p.status, p.current_version,"
			+ " p.created_at, p.updated_at, p.published_at, p.off_sale_at";
	private static final String VERSION_COLS = "v.id::text AS version_id, v.package_id::text, v.version AS package_version,"
			+ " v.title, v.description, v.price_cents, v.total_stock, v.fixed_redeem_deadline,"
			+ " v.valid_days_after_purchase, v.recommender_share_bps, v.platform_fee_bps,"
			+ " v.merchant_share_bps, v.policy_version, v.created_by::text, v.created_at AS version_created_at,"
			+ " v.recommender_fixed_cents";
	private static final String ORDER_COLS = "o.id::text, o.consumer_account_id::text, o.organization_id::text,"
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
	/**
	 * Read-side enrichment so orders expose the booked time slot without trusting
	 * current package versions.
	 */
	private static final String ORDER_SLOT_COLS = ", s.slot_start AS slot_start, s.slot_end AS slot_end";
	private static final String ORDER_SLOT_JOIN = " LEFT JOIN commerce_package_inventory_slot s ON s.id = o.inventory_slot_id";

	private final DatabaseClient db;

	public CommerceRepository(DatabaseClient db) {
		this.db = db;
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

	private static final String REDEMPTION_STATUSES_PREDICATE = " WHERE o.status IN ('redeeming', 'redeemed')";

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
						+ " refund_requested_amount_cents = :amount, refund_reason = :reason,"
						+ " last_error = NULL, version = version + 1, updated_at = now()"
						+ " WHERE o.id = CAST(:id AS uuid) AND o.status IN ('paid', 'partially_refunded')"
						+ " AND o.refunded_amount_cents + :amount <= o.price_cents RETURNING " + ORDER_COLS)
				.bind("id", id).bind("operationId", operationId).bind("amount", amountCents);
		spec = bindText(spec, "reason", reason);
		return spec.map(CommerceRepository::mapOrder).one();
	}

	public Flux<Order> claimExpired(int limit) {
		return db.sql("""
				WITH candidates AS (
				    SELECT id FROM consumer_order
				     WHERE status = 'paid' AND redeem_deadline <= now()
				     ORDER BY redeem_deadline FOR UPDATE SKIP LOCKED LIMIT :limit
				)
				UPDATE consumer_order o
				   SET status = 'refund_pending',
				       refund_operation_id = COALESCE(refund_operation_id, 'commerce-refund:' || o.id::text),
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
		return db.sql("UPDATE consumer_order o SET status = CASE"
				+ " WHEN o.refunded_amount_cents + o.refund_requested_amount_cents = o.price_cents"
				+ " THEN 'refunded' ELSE 'partially_refunded' END,"
				+ " refunded_amount_cents = o.refunded_amount_cents + o.refund_requested_amount_cents,"
				+ " refunded_at = CASE WHEN o.refunded_amount_cents + o.refund_requested_amount_cents = o.price_cents"
				+ " THEN now() ELSE o.refunded_at END, refund_requested_amount_cents = NULL,"
				+ " refund_operation_id = NULL, last_error = NULL, version = version + 1, updated_at = now()"
				+ " WHERE o.id = CAST(:id AS uuid) AND o.status = 'refund_pending'"
				+ " AND o.refund_requested_amount_cents IS NOT NULL RETURNING " + ORDER_COLS).bind("id", id)
				.map(CommerceRepository::mapOrder).one();
	}

	/**
	 * 任务书 #75 D3：核销直迁（paid→redeemed，跳过 redeeming 中间态）——核销码校验/过期守卫沿用
	 * {@code status='paid' AND redeem_deadline > now()}；同事务快照
	 * {@code split_eligible_at = 核销时刻 +
	 * 冷静期}（后续改配置不影响已核销单，与 payment_deadline 同款语义）+ 预写 split 幂等键。商家侧核销即刻成功， 分账由
	 * dispatcher 冷静期满后触发。
	 */
	public Mono<Order> markRedeemedWithCooldown(String id, String operationId, Instant splitEligibleAt) {
		return db
				.sql("UPDATE consumer_order o SET status = 'redeemed', redeemed_at = now(),"
						+ " split_operation_id = :operationId, split_eligible_at = :eligibleAt,"
						+ " last_error = NULL, version = version + 1, updated_at = now()"
						+ " WHERE o.id = CAST(:id AS uuid) AND o.status = 'paid' AND o.redeem_deadline > now()"
						+ " RETURNING " + ORDER_COLS)
				.bind("id", id).bind("operationId", operationId)
				.bind("eligibleAt", splitEligibleAt.atOffset(ZoneOffset.UTC)).map(CommerceRepository::mapOrder).one();
	}

	/**
	 * 任务书 #75 D3：分账完成标记（解耦后 redeemed 不再蕴含已分账，split_completed_at 是新的完成信号）。
	 * 兼容历史在途单：升级时刻卡在 redeeming 的旧行（无 split_eligible_at）由本方法一并收尾为 redeemed +
	 * split_completed。
	 */
	public Mono<Order> markSplitCompleted(String id) {
		return db
				.sql("UPDATE consumer_order o SET status = 'redeemed',"
						+ " redeemed_at = COALESCE(o.redeemed_at, now()), split_completed_at = now(),"
						+ " last_error = NULL, version = version + 1, updated_at = now()"
						+ " WHERE o.id = CAST(:id AS uuid) AND (o.status = 'redeemed' AND o.split_completed_at IS NULL"
						+ " OR o.status = 'redeeming')" + " RETURNING " + ORDER_COLS)
				.bind("id", id).map(CommerceRepository::mapOrder).one();
	}

	public Mono<Order> rebindAttribution(String id, String recommenderAccountId, int recommenderShareBps) {
		return db
				.sql("UPDATE consumer_order o SET recommender_account_id = CAST(:recommender AS uuid),"
						+ " recommender_share_bps = :recommenderBps,"
						+ " recommender_amount_cents = (o.price_cents * :recommenderBps) / 10000,"
						+ " merchant_share_bps = 10000 - o.platform_fee_bps - :recommenderBps,"
						+ " merchant_amount_cents = o.price_cents - o.platform_fee_cents"
						+ " - ((o.price_cents * :recommenderBps) / 10000),"
						+ " version = version + 1, updated_at = now()"
						+ " WHERE o.id = CAST(:id AS uuid) AND o.status IN ('paid', 'partially_refunded')"
						+ " RETURNING " + ORDER_COLS)
				.bind("id", id).bind("recommender", recommenderAccountId).bind("recommenderBps", recommenderShareBps)
				.map(CommerceRepository::mapOrder).one();
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

	// 任务书 #75 D5：replaceAttributionAllocations 已删——V37 表冻结增量（存量行仅供历史 redeeming 单
	// 分账与冲销读取），createOrder/rebindAttribution 均不再写入。

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

	public Mono<AfterSalesDispute> findAfterSalesDispute(String orderId) {
		return db.sql("""
				SELECT id::text, order_id::text, consumer_account_id::text, reason, status,
				       resolution, resolution_amount_cents, resolution_reason, refund_operation_id,
				       created_at, resolved_at
				  FROM consumer_order_after_sales_dispute
				 WHERE order_id = CAST(:orderId AS uuid)
				""").bind("orderId", orderId)
				.map(row -> new AfterSalesDispute(row.get("id", String.class), row.get("order_id", String.class),
						row.get("consumer_account_id", String.class), row.get("reason", String.class),
						row.get("status", String.class), row.get("resolution", String.class),
						row.get("resolution_amount_cents", Long.class), row.get("resolution_reason", String.class),
						row.get("refund_operation_id", String.class), instant(row, "created_at"),
						instant(row, "resolved_at")))
				.one();
	}

	public Mono<Order> requestDisputeRefund(String id, String operationId, long amountCents, String reason) {
		return db
				.sql("UPDATE consumer_order o SET status = 'refund_pending'," + " refund_operation_id = :operationId,"
						+ " refund_requested_amount_cents = :amount, refund_reason = :reason,"
						+ " version = version + 1, updated_at = now()"
						+ " WHERE o.id = CAST(:id AS uuid) AND o.status = 'after_sales_disputed'"
						+ " AND o.refunded_amount_cents + :amount <= o.price_cents RETURNING " + ORDER_COLS)
				.bind("id", id).bind("operationId", operationId).bind("amount", amountCents).bind("reason", reason)
				.map(CommerceRepository::mapOrder).one();
	}

	public Mono<Void> resolveAfterSalesDispute(String orderId, String resolution, long amountCents,
			String resolutionReason, String refundOperationId) {
		GenericExecuteSpec spec = db
				.sql("UPDATE consumer_order_after_sales_dispute SET status = :status, resolution = :resolution,"
						+ " resolution_amount_cents = :amount, resolution_reason = :reason,"
						+ " refund_operation_id = :refundOperationId, resolved_at = now()"
						+ " WHERE order_id = CAST(:orderId AS uuid) AND status = 'open'")
				.bind("orderId", orderId).bind("status", "refund".equals(resolution) ? "resolved" : "rejected")
				.bind("resolution", resolution).bind("amount", amountCents);
		spec = bindText(spec, "reason", resolutionReason);
		spec = bindText(spec, "refundOperationId", refundOperationId);
		return spec.then();
	}

	public Mono<Order> rejectAfterSalesDispute(String id) {
		return db
				.sql("UPDATE consumer_order SET status = CASE WHEN refunded_amount_cents > 0"
						+ " THEN 'partially_refunded' ELSE 'redeemed' END, version = version + 1, updated_at = now()"
						+ " WHERE id = CAST(:id AS uuid) AND status = 'after_sales_disputed' RETURNING " + ORDER_COLS)
				.bind("id", id).map(CommerceRepository::mapOrder).one();
	}

	/**
	 * 任务书 #75 D3：扫描状态集扩展 redeemed——冷静期已满且未完成分账的已核销单（未到期的行在 SQL 里过滤掉， 避免按 updated_at
	 * 反复空转）；redeeming 保持原样兼容升级时刻卡住的旧在途单（split_eligible_at 为 NULL， 视为立即可分账，由
	 * dispatcher 收尾）。
	 */
	public Flux<Order> pendingDispatch(int limit) {
		return db
				.sql("SELECT " + ORDER_COLS + " FROM consumer_order o"
						+ " WHERE o.status IN ('pending_payment', 'refund_pending', 'redeeming')"
						+ " OR (o.status = 'redeemed' AND o.split_completed_at IS NULL"
						+ " AND o.split_eligible_at IS NOT NULL AND o.split_eligible_at <= now())"
						+ " ORDER BY o.updated_at LIMIT :limit")
				.bind("limit", bounded(limit)).map(CommerceRepository::mapOrder).all();
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

	public Mono<Review> findReview(String orderId) {
		return db
				.sql("SELECT id::text, order_id::text, consumer_account_id::text, rating, comment, created_at"
						+ " FROM consumer_review WHERE order_id = CAST(:orderId AS uuid)")
				.bind("orderId", orderId).map(CommerceRepository::mapReview).one();
	}

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

	private static OfferVersion mapVersion(Readable row) {
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

	private static Order mapOrder(Readable row) {
		return order(row, null, null);
	}

	private static Order mapOrderWithSlot(Readable row) {
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

	private static Review mapReview(Readable row) {
		return new Review(row.get("id", String.class), row.get("order_id", String.class),
				row.get("consumer_account_id", String.class), row.get("rating", Integer.class),
				row.get("comment", String.class), instant(row, "created_at"));
	}

	private static Instant instant(Readable row, String name) {
		OffsetDateTime value = row.get(name, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private static int bounded(int limit) {
		return Math.max(1, Math.min(limit, 200));
	}
	private static String truncate(String value) {
		if (value == null || value.isBlank())
			return "unknown error";
		return value.length() <= 500 ? value : value.substring(0, 500);
	}
	private static GenericExecuteSpec bindUuid(GenericExecuteSpec spec, String name, String value) {
		return value == null || value.isBlank() ? spec.bindNull(name, String.class) : spec.bind(name, value);
	}
	private static GenericExecuteSpec bindText(GenericExecuteSpec spec, String name, String value) {
		return value == null || value.isBlank() ? spec.bindNull(name, String.class) : spec.bind(name, value);
	}
	private static GenericExecuteSpec bindInstant(GenericExecuteSpec spec, String name, Instant value) {
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

	/**
	 * 推荐官「我的推广」（任务书 #75 卡 B6）：本人 accepted 的套餐推广任务 + 按本人归因订单聚合的漏斗。
	 * 已核销未满冷静期（split_completed_at IS NULL）计 pending_settle，已分账计 settled。
	 */
	public Flux<RecommenderPromotion> recommenderPromotions(String accountId) {
		return db
				.sql("""
						SELECT t.id::text AS task_id, t.title AS task_title, t.status AS task_status,
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

	private static RecommenderPromotion mapRecommenderPromotion(Readable row) {
		return new RecommenderPromotion(row.get("task_id", String.class), row.get("task_title", String.class),
				row.get("task_status", String.class), row.get("package_id", String.class),
				row.get("package_title", String.class), row.get("price_cents", Long.class),
				row.get("recommender_share_bps", Integer.class),
				row.get("recommender_fixed_cents", Integer.class) == null
						? null
						: row.get("recommender_fixed_cents", Integer.class).longValue(),
				((Number) row.get("order_count", Long.class)).intValue(),
				((Number) row.get("redeemed_count", Long.class)).intValue(),
				row.get("pending_settle_cents", Long.class), row.get("settled_cents", Long.class));
	}

	private static MerchantPromotion mapMerchantPromotion(Readable row) {
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
			int orderCount, int redeemedCount, long pendingSettleCents, long settledCents) {
	}

	public record MerchantPromotion(String taskId, String taskTitle, String taskStatus, String packageId,
			String packageTitle, long priceCents, int orderCount, int redeemedCount, long pendingSettleCents,
			long settledCents, int refundedCount) {
	}
}
