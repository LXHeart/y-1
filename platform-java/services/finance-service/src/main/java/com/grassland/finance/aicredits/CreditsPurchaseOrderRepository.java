package com.grassland.finance.aicredits;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Map;

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

    /**
     * 三方对账（admin 只读）：逐单核对「订单 paid ⇔ purchase 流水存在 ⇔ AI_CREDIT_PURCHASE
     * 账本存在且借贷平衡」。返回不一致原因列表，供运营/演练取证。
     */
    public Mono<java.util.List<Map<String, Object>>> reconcile(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return db.sql("""
                        SELECT o.id::text AS order_id, o.status, o.price_cents, o.credits_amount,
                               (SELECT count(*) FROM credits_transaction t
                                 WHERE t.operation_id = 'purchase:' || o.id::text
                                   AND t.type = 'purchase') AS txn_count,
                               (SELECT count(*) FROM journal j
                                 WHERE j.operation_id = 'ai-credit-purchase:' || o.id::text) AS journal_count,
                               (SELECT coalesce(sum(CASE WHEN p.direction = 'DEBIT' THEN p.amount_cents
                                                         ELSE -p.amount_cents END), 0)
                                  FROM journal j JOIN posting p ON p.journal_id = j.id
                                 WHERE j.operation_id = 'ai-credit-purchase:' || o.id::text) AS ledger_balance
                        FROM credits_purchase_order o
                        ORDER BY o.created_at DESC LIMIT :limit
                        """)
                .bind("limit", safeLimit)
                .map(row -> {
                    String orderId = row.get("order_id", String.class);
                    String status = row.get("status", String.class);
                    long txnCount = row.get("txn_count", Long.class);
                    long journalCount = row.get("journal_count", Long.class);
                    long ledgerBalance = row.get("ledger_balance", Long.class);
                    java.util.List<String> reasons = new java.util.ArrayList<>();
                    if (!"paid".equals(status)) {
                        reasons.add("order_not_paid:" + status);
                    }
                    if (txnCount < 1) {
                        reasons.add("missing_purchase_txn");
                    }
                    if (journalCount < 1) {
                        reasons.add("missing_journal");
                    } else if (ledgerBalance != 0) {
                        reasons.add("ledger_unbalanced:" + ledgerBalance);
                    }
                    Map<String, Object> item = new java.util.LinkedHashMap<>();
                    item.put("orderId", orderId);
                    item.put("status", status);
                    item.put("priceCents", row.get("price_cents", Long.class));
                    item.put("creditsAmount", row.get("credits_amount", Integer.class));
                    item.put("consistent", reasons.isEmpty());
                    item.put("reasons", reasons);
                    return item;
                })
                .all()
                .collectList();
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
