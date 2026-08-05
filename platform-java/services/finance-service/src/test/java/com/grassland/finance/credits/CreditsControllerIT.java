package com.grassland.finance.credits;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.finance.FinanceItSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 积分域端到端（GL-P3-AI-001 下属切片）：award/consume/refund 的余额、流水、幂等与余额不足；
 * 内部端点共享密钥鉴权 + rejectForwarded；公共读端用户断言鉴权。
 *
 * <p>逻辑逐字移植自 legacy {@code credit.service.ts}，本组锁住最不能靠人工点一遍代替的并发与幂等不变量。
 */
class CreditsControllerIT extends FinanceItSupport {

    private static final String INTERNAL_KEY = "test-internal-key";

    @DynamicPropertySource
    static void creditsProps(DynamicPropertyRegistry r) {
        r.add("credits.internal-key", () -> INTERNAL_KEY);
    }

    @Test
    void awardThenConsumeUpdatesBalanceAndHistory() {
        String acct = UUID.randomUUID().toString();

        award(acct, 5);
        assertThat(balanceOf(acct)).isEqualTo(5);
        assertThat(earnedOf(acct)).isEqualTo(5);

        consume(acct, "comedy_generation", "op-" + acct);
        assertThat(balanceOf(acct)).isEqualTo(4);
        assertThat(spentOf(acct)).isEqualTo(1);

        // 公共读经用户断言
        client().get().uri("/api/credits/balance")
                .header("X-Grassland-Identity", sign(acct, "merchant", null, null))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.balance").isEqualTo(4)
                .jsonPath("$.totalEarned").isEqualTo(5)
                .jsonPath("$.totalSpent").isEqualTo(1);

        client().get().uri("/api/credits/history")
                .header("X-Grassland-Identity", sign(acct, "merchant", null, null))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.history[0].type").isEqualTo("consume")
                .jsonPath("$.history[0].feature").isEqualTo("comedy_generation")
                .jsonPath("$.history[0].amount").isEqualTo(-1)
                .jsonPath("$.history[1].type").isEqualTo("reward");
    }

    @Test
    void consumeIsInsufficientWithoutBalance() {
        String acct = UUID.randomUUID().toString();
        internalConsume(acct, "article_generation", "op-insuf")
                .expectStatus().isEqualTo(402)
                .expectBody().jsonPath("$.error").isEqualTo("积分不足");
        // 未扣成功：余额仍 0，无流水
        assertThat(balanceOf(acct)).isZero();
        assertThat(txnCount(acct)).isZero();
    }

    @Test
    void consumeIsIdempotentByOperationId() {
        String acct = UUID.randomUUID().toString();
        award(acct, 3);
        String op = "op-dedup-" + acct;

        internalConsume(acct, "video_analysis", op).expectStatus().isOk()
                .expectBody().jsonPath("$.data.deduplicated").isEqualTo(false);
        // 同 operationId 再投 → deduplicated，余额不再变
        internalConsume(acct, "video_analysis", op).expectStatus().isOk()
                .expectBody().jsonPath("$.data.deduplicated").isEqualTo(true);

        assertThat(balanceOf(acct)).isEqualTo(2);
        assertThat(txnCount(acct)).isEqualTo(2);   // 1 reward + 1 consume（重复投递未再插流水）
    }

    @Test
    void refundRestoresBalanceAndIsIdempotent() {
        String acct = UUID.randomUUID().toString();
        award(acct, 5);
        consume(acct, "comedy_generation", "consume-" + acct);   // balance 4
        assertThat(balanceOf(acct)).isEqualTo(4);

        // 退款键 = refund:<consumeId>（与 consume 行键区分，否则被 dedup 吞掉）
        client().post().uri("/internal/credits/refund")
                .header("X-Internal-Key", INTERNAL_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountId", acct, "amount", 1, "feature", "comedy_generation",
                        "operationId", "refund:consume-" + acct, "note", "上游失败自动退回"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.balance").isEqualTo(5);

        // 重复退款 → deduplicated，余额不再叠加
        client().post().uri("/internal/credits/refund")
                .header("X-Internal-Key", INTERNAL_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountId", acct, "amount", 1, "feature", "comedy_generation",
                        "operationId", "refund:consume-" + acct, "note", "重试"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.deduplicated").isEqualTo(true);
        assertThat(balanceOf(acct)).isEqualTo(5);
    }

    @Test
    void refundDoesNotCollideWithConsumeRow() {
        // consume 用 opId=X；若 refund 误用同一 X，会被 findOperation 命中 consume 行 → dedup 不退款（既有 bug）。
        // finance 存储 operation_id 原样，consume(X) 与 refund(refund:X) 是两行，互不影响。
        String acct = UUID.randomUUID().toString();
        award(acct, 2);
        consume(acct, "comedy_generation", "X-" + acct);   // balance 1
        // 误用原始 consume 键作 refund（错误用法）→ 命中 consume 行，dedup，不退款
        client().post().uri("/internal/credits/refund")
                .header("X-Internal-Key", INTERNAL_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountId", acct, "amount", 1, "feature", "comedy_generation",
                        "operationId", "X-" + acct, "note", "误用"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.deduplicated").isEqualTo(true);
        assertThat(balanceOf(acct)).isEqualTo(1);   // 未退款
    }

    @Test
    void internalEndpointsRequireSharedKey() {
        String acct = UUID.randomUUID().toString();
        // 无 X-Internal-Key → 401
        client().post().uri("/internal/credits/consume")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountId", acct, "feature", "comedy_generation", "operationId", "op"))
                .exchange().expectStatus().isUnauthorized();
        // 经代理（带 X-Forwarded-For）→ 404（rejectForwarded 纵深防御）
        client().post().uri("/internal/credits/consume")
                .header("X-Forwarded-For", "10.0.0.1")
                .header("X-Internal-Key", INTERNAL_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountId", acct, "feature", "comedy_generation", "operationId", "op"))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void publicReadsRequireUserAssertion() {
        // 无断言 → 401（credits 识人完全靠断言）
        client().get().uri("/api/credits/balance").exchange().expectStatus().isUnauthorized();
        // 断言指向自己 → 只读到自己余额（默认 0）
        String acct = UUID.randomUUID().toString();
        client().get().uri("/api/credits/balance")
                .header("X-Grassland-Identity", sign(acct, "merchant", null, null))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.balance").isEqualTo(0);
    }

    // ---------- helpers ----------

    private void award(String acct, int amount) {
        client().post().uri("/internal/credits/award")
                .header("X-Internal-Key", INTERNAL_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountId", acct, "amount", amount, "note", "test grant"))
                .exchange().expectStatus().isOk();
    }

    private void consume(String acct, String feature, String operationId) {
        internalConsume(acct, feature, operationId).expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec internalConsume(String acct, String feature, String operationId) {
        return client().post().uri("/internal/credits/consume")
                .header("X-Internal-Key", INTERNAL_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountId", acct, "feature", feature, "operationId", operationId))
                .exchange();
    }

    private int balanceOf(String acct) {
        Integer v = db.sql("SELECT balance FROM credits_account WHERE account_id = CAST(:a AS uuid)")
                .bind("a", acct).map(r -> r.get("balance", Integer.class)).one().block();
        return v == null ? 0 : v;
    }

    private int earnedOf(String acct) {
        Integer v = db.sql("SELECT total_earned FROM credits_account WHERE account_id = CAST(:a AS uuid)")
                .bind("a", acct).map(r -> r.get("total_earned", Integer.class)).one().block();
        return v == null ? 0 : v;
    }

    private int spentOf(String acct) {
        Integer v = db.sql("SELECT total_spent FROM credits_account WHERE account_id = CAST(:a AS uuid)")
                .bind("a", acct).map(r -> r.get("total_spent", Integer.class)).one().block();
        return v == null ? 0 : v;
    }

    private long txnCount(String acct) {
        Integer c = db.sql("SELECT COUNT(*)::int AS c FROM credits_transaction WHERE account_id = CAST(:a AS uuid)")
                .bind("a", acct).map(r -> r.get("c", Integer.class)).one().block();
        return c == null ? 0 : c.longValue();
    }
}
