package com.grassland.finance.aicredits;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 积分购买订单仓储（AI 套餐 v1 Slice B）。
 *
 * <p>{@code operation_id} 全局唯一 = 购买幂等键；{@link #insert} 冲突时返回 empty，
 * 由 {@link CreditsPurchaseService} 做回放匹配（镜像 {@code ConsumerPaymentRepository.insertPayment}）。
 */
@Component
public class CreditsPurchaseOrderRepository {

    private final DatabaseClient db;

    public CreditsPurchaseOrderRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 创建订单（冻结下单时的价格/面值快照）。operationId 冲突 → empty（幂等回放路径）。 */
    public Mono<PurchaseOrder> insert(String accountId, String packageId, String packageVersionId,
                                      long priceCents, int creditsAmount, String provider,
                                      String providerRef, String operationId) {
        return db.sql("""
                        INSERT INTO credits_purchase_order(
                            id, account_id, package_id, package_version_id, price_cents, credits_amount,
                            status, provider, provider_ref, operation_id)
                        VALUES (gen_random_uuid(), :accountId::uuid, :packageId::uuid, :packageVersionId::uuid,
                                :priceCents, :creditsAmount, 'created', :provider, :providerRef, :operationId)
                        ON CONFLICT (operation_id) DO NOTHING
                        RETURNING id::text, account_id::text, package_id::text, package_version_id::text,
                                  price_cents, credits_amount, status, provider, provider_ref, operation_id
                        """)
                .bind("accountId", accountId)
                .bind("packageId", packageId)
                .bind("packageVersionId", packageVersionId)
                .bind("priceCents", priceCents)
                .bind("creditsAmount", creditsAmount)
                .bind("provider", provider)
                .bind("providerRef", providerRef)
                .bind("operationId", operationId)
                .map(CreditsPurchaseOrderRepository::map).one();
    }

    public Mono<PurchaseOrder> findById(String orderId) {
        return byCondition("id = :key::uuid")
                .bind("key", orderId)
                .map(CreditsPurchaseOrderRepository::map)
                .one();
    }

    public Mono<PurchaseOrder> findByOperationId(String operationId) {
        return byCondition("operation_id = :key")
                .bind("key", operationId)
                .map(CreditsPurchaseOrderRepository::map)
                .one();
    }

    /** 本人订单倒序（默认最近 50 条）。 */
    public Flux<PurchaseOrder> findByAccount(String accountId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return db.sql("""
                        SELECT id::text, account_id::text, package_id::text, package_version_id::text,
                               price_cents, credits_amount, status, provider, provider_ref, operation_id
                        FROM credits_purchase_order
                        WHERE account_id = :accountId::uuid
                        ORDER BY created_at DESC LIMIT :limit
                        """)
                .bind("accountId", accountId)
                .bind("limit", safeLimit)
                .map(CreditsPurchaseOrderRepository::map)
                .all();
    }

    /** admin 全量订单倒序（购买监控）。 */
    public Flux<PurchaseOrder> listRecent(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return db.sql("""
                        SELECT id::text, account_id::text, package_id::text, package_version_id::text,
                               price_cents, credits_amount, status, provider, provider_ref, operation_id
                        FROM credits_purchase_order
                        ORDER BY created_at DESC LIMIT :limit
                        """)
                .bind("limit", safeLimit)
                .map(CreditsPurchaseOrderRepository::map)
                .all();
    }

    public Mono<Void> markPaid(String orderId) {
        return db.sql("UPDATE credits_purchase_order SET status = 'paid', paid_at = now() WHERE id = :id::uuid")
                .bind("id", orderId)
                .then();
    }

    private org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec byCondition(String condition) {
        return db.sql("""
                        SELECT id::text, account_id::text, package_id::text, package_version_id::text,
                               price_cents, credits_amount, status, provider, provider_ref, operation_id
                        FROM credits_purchase_order
                        WHERE """ + " " + condition);
    }

    private static PurchaseOrder map(io.r2dbc.spi.Readable row) {
        return new PurchaseOrder(
                row.get("id", String.class),
                row.get("account_id", String.class),
                row.get("package_id", String.class),
                row.get("package_version_id", String.class),
                row.get("price_cents", Long.class),
                row.get("credits_amount", Integer.class),
                row.get("status", String.class),
                row.get("provider", String.class),
                row.get("provider_ref", String.class),
                row.get("operation_id", String.class));
    }

    public record PurchaseOrder(
            String id, String accountId, String packageId, String packageVersionId,
            long priceCents, int creditsAmount, String status,
            String provider, String providerRef, String operationId) {
    }
}
