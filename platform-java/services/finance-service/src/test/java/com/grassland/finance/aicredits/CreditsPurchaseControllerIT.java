package com.grassland.finance.aicredits;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.finance.FinanceItSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 积分包购买编排（AI 套餐 v1 Slice B）。锁定：
 * 购买全链路（订单 paid + purchase 流水 + AI_CREDIT_PURCHASE 账本借贷平衡 + provider operation + outbox）、
 * operationId 幂等重放不双入账、draft 包 409、匿名 401、packages 只列 active、本人订单列表。
 */
class CreditsPurchaseControllerIT extends FinanceItSupport {

    private static final String BUYER = "11111111-1111-1111-1111-111111111111";
    private static final String HEADER = "X-Grassland-Identity";

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM credits_purchase_order").then().block();
        db.sql("UPDATE credits_package SET current_version_id = NULL").then().block();
        db.sql("DELETE FROM credits_package_version").then().block();
        db.sql("DELETE FROM credits_package").then().block();
        db.sql("DELETE FROM credits_transaction WHERE account_id = :acct::uuid")
                .bind("acct", BUYER).then().block();
        db.sql("DELETE FROM credits_account WHERE account_id = :acct::uuid")
                .bind("acct", BUYER).then().block();
        // 清理 AI 积分购买账本流水（走 V21 维护通道，裸 DELETE 已被触发器拦截）
        db.sql("SELECT ledger_maintenance_delete_journals('ai-credit-purchase:%')").then().block();
        db.sql("DELETE FROM finance_provider_operation WHERE operation_id LIKE 'ai-credit-purchase-op-%'")
                .then().block();
        db.sql("DELETE FROM finance_outbox WHERE event_type = 'AiCreditsPurchased'").then().block();
    }

    @Test
    @DisplayName("购买：订单 paid + purchase 流水 + 账本借贷平衡 + provider operation + outbox + 余额")
    void purchaseGrantsCreditsAndPostsLedger() {
        String packageId = seedActivePackage("体验包", 990, 10);

        client().post().uri("/api/credits/purchase-orders")
                .header(HEADER, sign(BUYER, "merchant", null, null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("packageId", packageId,
                        "operationId", "ai-credit-purchase-op-1"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.status").isEqualTo("paid")
                .jsonPath("$.data.creditsAmount").isEqualTo(10)
                .jsonPath("$.data.balance").isEqualTo(10);

        // purchase 流水（幂等键 purchase:<orderId>）
        String txnOperation = db.sql("""
                        SELECT operation_id FROM credits_transaction
                        WHERE account_id = :acct::uuid AND type = 'purchase'
                        """)
                .bind("acct", BUYER).map(row -> row.get("operation_id", String.class)).one().block();
        assertThat(txnOperation).startsWith("purchase:");

        // 账本：AI_CREDIT_PURCHASE，借贷合计为零，贷方 AI_CREDIT_REVENUE（复合键直查 posting）
        List<String> debitCredit = db.sql("""
                        SELECT p.direction AS d, p.account_type AS t, p.amount_cents AS a
                        FROM journal j JOIN posting p ON p.journal_id = j.id
                        WHERE j.operation_id LIKE 'ai-credit-purchase:%'
                        """)
                .map(row -> row.get("d", String.class) + ":" + row.get("t", String.class)
                        + ":" + row.get("a", Long.class))
                .all().collectList().block();
        assertThat(debitCredit).containsExactlyInAnyOrder(
                "DEBIT:EXTERNAL:990", "CREDIT:AI_CREDIT_REVENUE:990");

        // provider operation（对账事实）+ outbox 事件
        Long providerOps = db.sql(
                        "SELECT count(*) AS n FROM finance_provider_operation WHERE amount_cents = 990")
                .map(row -> row.get("n", Long.class)).one().block();
        assertThat(providerOps).isEqualTo(1);
        Long events = db.sql("SELECT count(*) AS n FROM finance_outbox WHERE event_type = 'AiCreditsPurchased'")
                .map(row -> row.get("n", Long.class)).one().block();
        assertThat(events).isEqualTo(1);
    }

    @Test
    @DisplayName("同 operationId 重放：返回同一订单，余额/流水/账本不翻倍")
    void purchaseIsIdempotent() {
        String packageId = seedActivePackage("创作包", 4900, 60);

        Map<String, Object> first = postPurchase(packageId, "ai-credit-purchase-op-2");
        Map<String, Object> replay = postPurchase(packageId, "ai-credit-purchase-op-2");

        assertThat(first.get("orderId")).isEqualTo(replay.get("orderId"));
        Integer balance = db.sql("SELECT balance FROM credits_account WHERE account_id = :acct::uuid")
                .bind("acct", BUYER).map(row -> row.get("balance", Integer.class)).one().block();
        assertThat(balance).isEqualTo(60);
        Long txns = db.sql("""
                        SELECT count(*) AS n FROM credits_transaction
                        WHERE account_id = :acct::uuid AND type = 'purchase'
                        """)
                .bind("acct", BUYER).map(row -> row.get("n", Long.class)).one().block();
        assertThat(txns).isEqualTo(1);
        Long journals = db.sql("SELECT count(*) AS n FROM journal WHERE memo LIKE 'ai credits purchase%'")
                .map(row -> row.get("n", Long.class)).one().block();
        assertThat(journals).isEqualTo(1);
    }

    @Test
    @DisplayName("draft 包购买 → 409；匿名 → 401")
    void draftPackageAndAnonymousRejected() {
        String draftId = seedDraftPackage("未上架包", 100, 1);
        client().post().uri("/api/credits/purchase-orders")
                .header(HEADER, sign(BUYER, "merchant", null, null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("packageId", draftId))
                .exchange().expectStatus().isEqualTo(409);

        client().post().uri("/api/credits/purchase-orders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("packageId", draftId))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("packages 列表只含 active；本人订单倒序")
    void packagesListOnlyActiveAndMyOrders() {
        seedActivePackage("在售包", 990, 10);
        seedDraftPackage("草稿包", 100, 1);

        client().get().uri("/api/credits/packages")
                .header(HEADER, sign(BUYER, "merchant", null, null))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].name").isEqualTo("在售包");

        String packageId = seedActivePackage("二次包", 199, 2);
        postPurchase(packageId, "ai-credit-purchase-op-3");
        client().get().uri("/api/credits/purchase-orders")
                .header(HEADER, sign(BUYER, "merchant", null, null))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].status").isEqualTo("paid")
                .jsonPath("$.data[0].priceCents").isEqualTo(199);
    }

    @Test
    @DisplayName("对账端点：逐单三方核对（订单×purchase流水×账本平衡），篡改流水后进 inconsistent")
    void reconciliationDetectsTampering() {
        String packageId = seedActivePackage("对账包", 590, 5);
        postPurchase(packageId, "ai-credit-purchase-op-4");

        client().get().uri("/api/admin/credits-purchase-orders/reconciliation")
                .header(HEADER, signRole("recon-admin", "finance"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.totalOrders").isEqualTo(1)
                .jsonPath("$.data.inconsistent.length()").isEqualTo(0);

        // 篡改：删掉 purchase 流水 → 该单必须进 inconsistent
        db.sql("DELETE FROM credits_transaction WHERE type = 'purchase' AND account_id = :acct::uuid")
                .bind("acct", BUYER).then().block();
        client().get().uri("/api/admin/credits-purchase-orders/reconciliation")
                .header(HEADER, signRole("recon-admin", "finance"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.totalOrders").isEqualTo(1)
                .jsonPath("$.data.inconsistent.length()").isEqualTo(1)
                .jsonPath("$.data.inconsistent[0].reasons").isArray();
    }

    // ---------------- helpers ----------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> postPurchase(String packageId, String operationId) {
        return client().post().uri("/api/credits/purchase-orders")
                .header(HEADER, sign(BUYER, "merchant", null, null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("packageId", packageId, "operationId", operationId))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postPurchase(String packageId) {
        return client().post().uri("/api/credits/purchase-orders")
                .header(HEADER, sign(BUYER, "merchant", null, null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("packageId", packageId))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
    }

    private String seedActivePackage(String name, long priceCents, int creditsAmount) {
        String id = seedDraftPackage(name, priceCents, creditsAmount);
        db.sql("UPDATE credits_package SET status = 'active' WHERE id = :id::uuid")
                .bind("id", id).then().block();
        return id;
    }

    private String seedDraftPackage(String name, long priceCents, int creditsAmount) {
        db.sql("""
                        INSERT INTO credits_package(id, name, description, status)
                        VALUES (gen_random_uuid(), :name, '', 'draft')
                        """)
                .bind("name", name)
                .then()
                .then(db.sql("""
                                INSERT INTO credits_package_version(id, package_id, version, price_cents, credits_amount)
                                SELECT gen_random_uuid(), id, 1, :priceCents, :creditsAmount
                                FROM credits_package WHERE name = :name
                                """)
                        .bind("priceCents", priceCents)
                        .bind("creditsAmount", creditsAmount)
                        .bind("name", name)
                        .then())
                .then(db.sql("""
                                UPDATE credits_package SET current_version_id =
                                      (SELECT id FROM credits_package_version
                                       WHERE package_id = credits_package.id ORDER BY version DESC LIMIT 1)
                                WHERE name = :name
                                """)
                        .bind("name", name)
                        .then())
                .block();
        return db.sql("SELECT id::text FROM credits_package WHERE name = :name")
                .bind("name", name).map(row -> row.get("id", String.class)).one().block();
    }
}
