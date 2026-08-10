package com.grassland.marketplace.commerce;

import com.grassland.marketplace.commerce.CommerceModels.Offer;
import com.grassland.marketplace.commerce.CommerceModels.OfferDetail;
import com.grassland.marketplace.commerce.CommerceModels.OfferVersion;
import com.grassland.marketplace.commerce.CommerceModels.Order;
import com.grassland.marketplace.commerce.CommerceModels.Review;
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
            + " v.merchant_share_bps, v.policy_version, v.created_by::text, v.created_at AS version_created_at";
    private static final String ORDER_COLS = "o.id::text, o.consumer_account_id::text, o.organization_id::text,"
            + " o.store_id::text, o.task_id::text, o.package_id::text, o.package_version_id::text,"
            + " o.package_version, o.package_title, o.recommender_account_id::text, o.price_cents,"
            + " o.recommender_share_bps, o.platform_fee_bps, o.merchant_share_bps,"
            + " o.recommender_amount_cents, o.platform_fee_cents, o.merchant_amount_cents,"
            + " o.policy_version, o.status, o.redeem_code_hash, o.redeem_deadline,"
            + " o.payment_operation_id, o.refund_operation_id, o.split_operation_id, o.provider_ref,"
            + " o.last_error, o.version, o.created_at, o.paid_at, o.redeemed_at, o.refunded_at, o.updated_at";

    private final DatabaseClient db;

    public CommerceRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Offer> insertOffer(
            String id, String ownerAccountId, String organizationId, String storeId, String taskId) {
        GenericExecuteSpec spec = db.sql("""
                INSERT INTO commerce_package(
                    id, organization_id, store_id, task_id, owner_account_id, status, current_version)
                VALUES (CAST(:id AS uuid), CAST(:org AS uuid), CAST(:store AS uuid), CAST(:task AS uuid),
                        CAST(:owner AS uuid), 'draft', 1)
                RETURNING id::text, organization_id::text, store_id::text, task_id::text,
                          owner_account_id::text, status, current_version, created_at, updated_at,
                          published_at, off_sale_at
                """)
                .bind("id", id).bind("org", organizationId).bind("owner", ownerAccountId);
        spec = bindUuid(spec, "store", storeId);
        spec = bindUuid(spec, "task", taskId);
        return spec.map(CommerceRepository::mapOfferUnaliased).one();
    }

    public Mono<OfferVersion> insertVersion(
            String id, String packageId, int version, OfferInput input, String createdBy) {
        GenericExecuteSpec spec = db.sql("""
                INSERT INTO commerce_package_version(
                    id, package_id, version, title, description, price_cents, total_stock,
                    fixed_redeem_deadline, valid_days_after_purchase, recommender_share_bps,
                    platform_fee_bps, merchant_share_bps, policy_version, created_by)
                VALUES (CAST(:id AS uuid), CAST(:packageId AS uuid), :version, :title, :description,
                        :price, :stock, :fixedDeadline, :validDays, :recommenderBps, :platformBps,
                        :merchantBps, :policyVersion, CAST(:createdBy AS uuid))
                RETURNING id::text AS version_id, package_id::text, version AS package_version,
                          title, description, price_cents, total_stock, fixed_redeem_deadline,
                          valid_days_after_purchase, recommender_share_bps, platform_fee_bps,
                          merchant_share_bps, policy_version, created_by::text,
                          created_at AS version_created_at
                """)
                .bind("id", id).bind("packageId", packageId).bind("version", version)
                .bind("title", input.title()).bind("price", input.priceCents())
                .bind("stock", input.totalStock()).bind("recommenderBps", input.recommenderShareBps())
                .bind("platformBps", input.platformFeeBps()).bind("merchantBps", input.merchantShareBps())
                .bind("policyVersion", input.policyVersion()).bind("createdBy", createdBy);
        spec = bindText(spec, "description", input.description());
        spec = bindInstant(spec, "fixedDeadline", input.fixedRedeemDeadline());
        spec = input.validDaysAfterPurchase() == null
                ? spec.bindNull("validDays", Integer.class)
                : spec.bind("validDays", input.validDaysAfterPurchase());
        return spec.map(CommerceRepository::mapVersion).one();
    }

    public Mono<Void> insertInventory(String versionId, int totalStock) {
        return db.sql("""
                INSERT INTO commerce_package_inventory(package_version_id, total_stock, remaining_stock)
                VALUES (CAST(:versionId AS uuid), :stock, :stock)
                """)
                .bind("versionId", versionId).bind("stock", totalStock).then();
    }

    public Mono<Offer> setCurrentVersion(String packageId, int expectedVersion, int nextVersion) {
        return db.sql("""
                UPDATE commerce_package
                   SET current_version = :nextVersion, updated_at = now()
                 WHERE id = CAST(:id AS uuid) AND current_version = :expectedVersion
                RETURNING id::text, organization_id::text, store_id::text, task_id::text,
                          owner_account_id::text, status, current_version, created_at, updated_at,
                          published_at, off_sale_at
                """)
                .bind("id", packageId).bind("expectedVersion", expectedVersion)
                .bind("nextVersion", nextVersion)
                .map(CommerceRepository::mapOfferUnaliased).one();
    }

    public Mono<Offer> findOffer(String id) {
        return db.sql("SELECT " + OFFER_COLS + " FROM commerce_package p WHERE p.id = CAST(:id AS uuid)")
                .bind("id", id).map(CommerceRepository::mapOffer).one();
    }

    public Mono<OfferDetail> findDetail(String id) {
        return db.sql("SELECT " + OFFER_COLS + ", " + VERSION_COLS
                        + ", i.remaining_stock FROM commerce_package p"
                        + " JOIN commerce_package_version v ON v.package_id = p.id AND v.version = p.current_version"
                        + " JOIN commerce_package_inventory i ON i.package_version_id = v.id"
                        + " WHERE p.id = CAST(:id AS uuid)")
                .bind("id", id).map(CommerceRepository::mapDetail).one();
    }

    public Flux<OfferDetail> listOffers(String organizationId, String storeId) {
        String storePredicate = storeId == null || storeId.isBlank()
                ? "p.store_id IS NULL" : "p.store_id = CAST(:store AS uuid)";
        GenericExecuteSpec spec = db.sql("SELECT " + OFFER_COLS + ", " + VERSION_COLS
                        + ", i.remaining_stock FROM commerce_package p"
                        + " JOIN commerce_package_version v ON v.package_id = p.id AND v.version = p.current_version"
                        + " JOIN commerce_package_inventory i ON i.package_version_id = v.id"
                        + " WHERE p.organization_id = CAST(:org AS uuid) AND " + storePredicate
                        + " ORDER BY p.updated_at DESC")
                .bind("org", organizationId);
        if (storeId != null && !storeId.isBlank()) spec = spec.bind("store", storeId);
        return spec.map(CommerceRepository::mapDetail).all();
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
        return db.sql("UPDATE commerce_package SET status = :status, " + timestamps
                        + ", updated_at = now() WHERE id = CAST(:id AS uuid) RETURNING "
                        + "id::text, organization_id::text, store_id::text, task_id::text,"
                        + " owner_account_id::text, status, current_version, created_at, updated_at,"
                        + " published_at, off_sale_at")
                .bind("id", id).bind("status", status)
                .map(CommerceRepository::mapOfferUnaliased).one();
    }

    public Mono<Integer> reserveInventory(String versionId) {
        return db.sql("""
                UPDATE commerce_package_inventory
                   SET remaining_stock = remaining_stock - 1, updated_at = now()
                 WHERE package_version_id = CAST(:versionId AS uuid) AND remaining_stock > 0
                RETURNING remaining_stock
                """)
                .bind("versionId", versionId)
                .map(row -> row.get("remaining_stock", Integer.class)).one();
    }

    public Mono<Void> replenishInventory(String versionId) {
        return db.sql("""
                UPDATE commerce_package_inventory
                   SET remaining_stock = LEAST(total_stock, remaining_stock + 1), updated_at = now()
                 WHERE package_version_id = CAST(:versionId AS uuid)
                """)
                .bind("versionId", versionId).then();
    }

    public Mono<Order> insertOrder(NewOrder order) {
        GenericExecuteSpec spec = db.sql("""
                INSERT INTO consumer_order(
                    id, consumer_account_id, organization_id, store_id, task_id, package_id,
                    package_version_id, package_version, package_title, recommender_account_id,
                    price_cents, recommender_share_bps, platform_fee_bps, merchant_share_bps,
                    recommender_amount_cents, platform_fee_cents, merchant_amount_cents,
                    policy_version, status, redeem_code_hash, redeem_deadline, payment_operation_id)
                VALUES (CAST(:id AS uuid), CAST(:consumer AS uuid), CAST(:org AS uuid), CAST(:store AS uuid),
                        CAST(:task AS uuid), CAST(:packageId AS uuid), CAST(:packageVersionId AS uuid),
                        :packageVersion, :packageTitle, CAST(:recommender AS uuid), :price,
                        :recommenderBps, :platformBps, :merchantBps, :recommenderAmount,
                        :platformAmount, :merchantAmount, :policyVersion, 'pending_payment',
                        :codeHash, :deadline, :paymentOperationId)
                RETURNING %s
                """.formatted(ORDER_COLS.replace("o.", "")))
                .bind("id", order.id()).bind("consumer", order.consumerAccountId())
                .bind("org", order.organizationId()).bind("packageId", order.packageId())
                .bind("packageVersionId", order.packageVersionId()).bind("packageVersion", order.packageVersion())
                .bind("packageTitle", order.packageTitle()).bind("price", order.priceCents())
                .bind("recommenderBps", order.recommenderShareBps()).bind("platformBps", order.platformFeeBps())
                .bind("merchantBps", order.merchantShareBps()).bind("recommenderAmount", order.recommenderAmountCents())
                .bind("platformAmount", order.platformFeeCents()).bind("merchantAmount", order.merchantAmountCents())
                .bind("policyVersion", order.policyVersion()).bind("codeHash", order.redeemCodeHash())
                .bind("deadline", order.redeemDeadline().atOffset(ZoneOffset.UTC))
                .bind("paymentOperationId", order.paymentOperationId());
        spec = bindUuid(spec, "store", order.storeId());
        spec = bindUuid(spec, "task", order.taskId());
        spec = bindUuid(spec, "recommender", order.recommenderAccountId());
        return spec.map(CommerceRepository::mapOrder).one();
    }

    public Mono<Order> findOrder(String id) {
        return db.sql("SELECT " + ORDER_COLS + " FROM consumer_order o WHERE o.id = CAST(:id AS uuid)")
                .bind("id", id).map(CommerceRepository::mapOrder).one();
    }

    public Mono<Order> findOrderByCodeHash(String hash) {
        return db.sql("SELECT " + ORDER_COLS + " FROM consumer_order o WHERE o.redeem_code_hash = :hash")
                .bind("hash", hash).map(CommerceRepository::mapOrder).one();
    }

    public Flux<Order> listConsumerOrders(String accountId, int limit) {
        return db.sql("SELECT " + ORDER_COLS + " FROM consumer_order o"
                        + " WHERE o.consumer_account_id = CAST(:accountId AS uuid)"
                        + " ORDER BY o.created_at DESC LIMIT :limit")
                .bind("accountId", accountId).bind("limit", bounded(limit))
                .map(CommerceRepository::mapOrder).all();
    }

    public Flux<Order> listMerchantOrders(String organizationId, String storeId, int limit) {
        String storePredicate = storeId == null || storeId.isBlank()
                ? "o.store_id IS NULL" : "o.store_id = CAST(:store AS uuid)";
        GenericExecuteSpec spec = db.sql("SELECT " + ORDER_COLS + " FROM consumer_order o"
                        + " WHERE o.organization_id = CAST(:org AS uuid) AND " + storePredicate
                        + " ORDER BY o.created_at DESC LIMIT :limit")
                .bind("org", organizationId).bind("limit", bounded(limit));
        if (storeId != null && !storeId.isBlank()) spec = spec.bind("store", storeId);
        return spec.map(CommerceRepository::mapOrder).all();
    }

    public Flux<Order> listAdminOrders(String status, int limit) {
        String predicate = status == null || status.isBlank() ? "" : " WHERE o.status = :status";
        GenericExecuteSpec spec = db.sql("SELECT " + ORDER_COLS + " FROM consumer_order o" + predicate
                        + " ORDER BY o.created_at DESC LIMIT :limit")
                .bind("limit", bounded(limit));
        if (!predicate.isEmpty()) spec = spec.bind("status", status);
        return spec.map(CommerceRepository::mapOrder).all();
    }

    public Mono<Order> markPaid(String id, String providerRef) {
        return db.sql("UPDATE consumer_order o SET status = 'paid', provider_ref = :providerRef,"
                        + " paid_at = now(), last_error = NULL, version = version + 1, updated_at = now()"
                        + " WHERE o.id = CAST(:id AS uuid) AND o.status = 'pending_payment' RETURNING " + ORDER_COLS)
                .bind("id", id).bind("providerRef", providerRef)
                .map(CommerceRepository::mapOrder).one();
    }

    public Mono<Void> recordError(String id, String status, String message) {
        return db.sql("UPDATE consumer_order SET last_error = :message, updated_at = now()"
                        + " WHERE id = CAST(:id AS uuid) AND status = :status")
                .bind("id", id).bind("status", status)
                .bind("message", truncate(message)).then();
    }

    public Mono<Order> requestRefund(String id, String operationId) {
        return db.sql("UPDATE consumer_order o SET status = 'refund_pending', refund_operation_id = :operationId,"
                        + " last_error = NULL, version = version + 1, updated_at = now()"
                        + " WHERE o.id = CAST(:id AS uuid) AND o.status = 'paid' RETURNING " + ORDER_COLS)
                .bind("id", id).bind("operationId", operationId)
                .map(CommerceRepository::mapOrder).one();
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
                       version = version + 1, updated_at = now()
                  FROM candidates WHERE o.id = candidates.id
                RETURNING %s
                """.formatted(ORDER_COLS))
                .bind("limit", bounded(limit)).map(CommerceRepository::mapOrder).all();
    }

    public Mono<Order> markRefunded(String id) {
        return db.sql("UPDATE consumer_order o SET status = 'refunded', refunded_at = now(),"
                        + " last_error = NULL, version = version + 1, updated_at = now()"
                        + " WHERE o.id = CAST(:id AS uuid) AND o.status = 'refund_pending' RETURNING " + ORDER_COLS)
                .bind("id", id).map(CommerceRepository::mapOrder).one();
    }

    public Mono<Order> markRedeeming(String id, String operationId) {
        return db.sql("UPDATE consumer_order o SET status = 'redeeming', split_operation_id = :operationId,"
                        + " last_error = NULL, version = version + 1, updated_at = now()"
                        + " WHERE o.id = CAST(:id AS uuid) AND o.status = 'paid' AND o.redeem_deadline > now()"
                        + " RETURNING " + ORDER_COLS)
                .bind("id", id).bind("operationId", operationId)
                .map(CommerceRepository::mapOrder).one();
    }

    public Mono<Order> markRedeemed(String id) {
        return db.sql("UPDATE consumer_order o SET status = 'redeemed', redeemed_at = now(),"
                        + " last_error = NULL, version = version + 1, updated_at = now()"
                        + " WHERE o.id = CAST(:id AS uuid) AND o.status = 'redeeming' RETURNING " + ORDER_COLS)
                .bind("id", id).map(CommerceRepository::mapOrder).one();
    }

    public Flux<Order> pendingDispatch(int limit) {
        return db.sql("SELECT " + ORDER_COLS + " FROM consumer_order o"
                        + " WHERE o.status IN ('pending_payment', 'refund_pending', 'redeeming')"
                        + " ORDER BY o.updated_at LIMIT :limit")
                .bind("limit", bounded(limit)).map(CommerceRepository::mapOrder).all();
    }

    public Mono<Review> insertReview(String orderId, String accountId, int rating, String comment) {
        GenericExecuteSpec spec = db.sql("""
                INSERT INTO consumer_review(id, order_id, consumer_account_id, rating, comment)
                VALUES (CAST(:id AS uuid), CAST(:orderId AS uuid), CAST(:accountId AS uuid), :rating, :comment)
                ON CONFLICT (order_id) DO NOTHING
                RETURNING id::text, order_id::text, consumer_account_id::text, rating, comment, created_at
                """)
                .bind("id", UUID.randomUUID().toString()).bind("orderId", orderId)
                .bind("accountId", accountId).bind("rating", rating);
        spec = bindText(spec, "comment", comment);
        return spec.map(CommerceRepository::mapReview).one();
    }

    public Mono<Review> findReview(String orderId) {
        return db.sql("SELECT id::text, order_id::text, consumer_account_id::text, rating, comment, created_at"
                        + " FROM consumer_review WHERE order_id = CAST(:orderId AS uuid)")
                .bind("orderId", orderId).map(CommerceRepository::mapReview).one();
    }

    private static OfferDetail mapDetail(Readable row) {
        return new OfferDetail(mapOffer(row), mapVersion(row), row.get("remaining_stock", Integer.class));
    }

    private static Offer mapOffer(Readable row) {
        return new Offer(row.get("id", String.class), row.get("organization_id", String.class),
                row.get("store_id", String.class), row.get("task_id", String.class),
                row.get("owner_account_id", String.class), row.get("status", String.class),
                row.get("current_version", Integer.class), instant(row, "created_at"),
                instant(row, "updated_at"), instant(row, "published_at"), instant(row, "off_sale_at"));
    }

    private static Offer mapOfferUnaliased(Readable row) { return mapOffer(row); }

    private static OfferVersion mapVersion(Readable row) {
        return new OfferVersion(row.get("version_id", String.class), row.get("package_id", String.class),
                row.get("package_version", Integer.class), row.get("title", String.class),
                row.get("description", String.class), row.get("price_cents", Long.class),
                row.get("total_stock", Integer.class), instant(row, "fixed_redeem_deadline"),
                row.get("valid_days_after_purchase", Integer.class), row.get("recommender_share_bps", Integer.class),
                row.get("platform_fee_bps", Integer.class), row.get("merchant_share_bps", Integer.class),
                row.get("policy_version", String.class), row.get("created_by", String.class),
                instant(row, "version_created_at"));
    }

    private static Order mapOrder(Readable row) {
        return new Order(row.get("id", String.class), row.get("consumer_account_id", String.class),
                row.get("organization_id", String.class), row.get("store_id", String.class),
                row.get("task_id", String.class), row.get("package_id", String.class),
                row.get("package_version_id", String.class), row.get("package_version", Integer.class),
                row.get("package_title", String.class), row.get("recommender_account_id", String.class),
                row.get("price_cents", Long.class), row.get("recommender_share_bps", Integer.class),
                row.get("platform_fee_bps", Integer.class), row.get("merchant_share_bps", Integer.class),
                row.get("recommender_amount_cents", Long.class), row.get("platform_fee_cents", Long.class),
                row.get("merchant_amount_cents", Long.class), row.get("policy_version", String.class),
                row.get("status", String.class), row.get("redeem_code_hash", String.class),
                instant(row, "redeem_deadline"), row.get("payment_operation_id", String.class),
                row.get("refund_operation_id", String.class), row.get("split_operation_id", String.class),
                row.get("provider_ref", String.class), row.get("last_error", String.class),
                row.get("version", Integer.class), instant(row, "created_at"), instant(row, "paid_at"),
                instant(row, "redeemed_at"), instant(row, "refunded_at"), instant(row, "updated_at"));
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

    private static int bounded(int limit) { return Math.max(1, Math.min(limit, 200)); }
    private static String truncate(String value) {
        if (value == null || value.isBlank()) return "unknown error";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
    private static GenericExecuteSpec bindUuid(GenericExecuteSpec spec, String name, String value) {
        return value == null || value.isBlank() ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }
    private static GenericExecuteSpec bindText(GenericExecuteSpec spec, String name, String value) {
        return value == null || value.isBlank() ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }
    private static GenericExecuteSpec bindInstant(GenericExecuteSpec spec, String name, Instant value) {
        return value == null ? spec.bindNull(name, OffsetDateTime.class)
                : spec.bind(name, value.atOffset(ZoneOffset.UTC));
    }

    public record OfferInput(
            String title, String description, long priceCents, int totalStock,
            Instant fixedRedeemDeadline, Integer validDaysAfterPurchase,
            int recommenderShareBps, int platformFeeBps, int merchantShareBps,
            String policyVersion) {}

    public record NewOrder(
            String id, String consumerAccountId, String organizationId, String storeId, String taskId,
            String packageId, String packageVersionId, int packageVersion, String packageTitle,
            String recommenderAccountId, long priceCents, int recommenderShareBps, int platformFeeBps,
            int merchantShareBps, long recommenderAmountCents, long platformFeeCents,
            long merchantAmountCents, String policyVersion, String redeemCodeHash,
            Instant redeemDeadline, String paymentOperationId) {}
}
