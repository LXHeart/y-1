package com.grassland.finance.credits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.finance.FinanceItSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 积分域端到端（GL-P3-AI-001 下属切片）：award/consume/refund 的余额、流水、幂等与余额不足；
 * 内部端点服务断言鉴权 + rejectForwarded；公共读端用户断言鉴权。
 *
 * <p>逻辑逐字移植自 legacy {@code credit.service.ts}，本组锁住最不能靠人工点一遍代替的并发与幂等不变量。
 */
class CreditsControllerIT extends FinanceItSupport {

    @org.springframework.beans.factory.annotation.Autowired
    TransactionalOperator transactions;

    @DynamicPropertySource
    static void creditsProps(DynamicPropertyRegistry r) {
        r.add("credits.ai-quota.base-daily", () -> "2");
        r.add("credits.ai-quota.zone-id", () -> "Asia/Shanghai");
        r.add("credits.cents-policy.version", () -> "test-v1");
        r.add("credits.cents-policy.effective-at", () -> "2026-01-01T00:00:00Z");
        r.add("credits.cents-policy.rounding", () -> "HALF_UP");
        r.add("credits.cents-policy.cents-numerator", () -> "100");
        r.add("credits.cents-policy.credits-denominator", () -> "1");
        r.add("credits.cents-policy.max-cents-per-operation", () -> "100000");
    }

    @Test
    void usageSettlementReturnsUnusedPaidReservationAndIsIdempotent() {
        String acct = UUID.randomUUID().toString();
        String operationId = UUID.randomUUID().toString();
        award(acct, 5);

        reserveUsage(acct, operationId, 300, null, null).expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.source").isEqualTo("paid")
                .jsonPath("$.data.reservedCredits").isEqualTo(3)
                .jsonPath("$.data.balance").isEqualTo(2);
        settleUsage(acct, operationId, 100).expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.actualCredits").isEqualTo(1)
                .jsonPath("$.data.adjustmentCredits").isEqualTo(-2)
                .jsonPath("$.data.balance").isEqualTo(4)
                .jsonPath("$.data.deduplicated").isEqualTo(false);

        settleUsage(acct, operationId, 100).expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.balance").isEqualTo(4)
                .jsonPath("$.data.deduplicated").isEqualTo(true);
        settleUsage(acct, operationId, 200).expectStatus().isEqualTo(409);

        assertThat(balanceOf(acct)).isEqualTo(4);
        assertThat(spentOf(acct)).isEqualTo(1);
        assertThat(usageAdjustmentCount(operationId)).isEqualTo(1);
    }

    @Test
    void usageSettlementChargesActualCostAboveReservation() {
        String acct = UUID.randomUUID().toString();
        String operationId = UUID.randomUUID().toString();
        award(acct, 4);

        reserveUsage(acct, operationId, 100, null, null).expectStatus().isOk();
        settleUsage(acct, operationId, 300).expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.reservedCredits").isEqualTo(1)
                .jsonPath("$.data.actualCredits").isEqualTo(3)
                .jsonPath("$.data.adjustmentCredits").isEqualTo(2)
                .jsonPath("$.data.balance").isEqualTo(1);

        assertThat(balanceOf(acct)).isEqualTo(1);
        assertThat(spentOf(acct)).isEqualTo(3);
        assertThat(usageAdjustmentCount(operationId)).isEqualTo(1);
    }

    @Test
    void usageSettlementLeavesReservationOpenWhenAdditionalCreditsAreInsufficient() {
        String acct = UUID.randomUUID().toString();
        String operationId = UUID.randomUUID().toString();
        award(acct, 1);

        reserveUsage(acct, operationId, 100, null, null).expectStatus().isOk();
        settleUsage(acct, operationId, 300).expectStatus().isEqualTo(402);

        assertThat(balanceOf(acct)).isZero();
        assertThat(spentOf(acct)).isEqualTo(1);
        assertThat(usageAdjustmentCount(operationId)).isZero();
        assertThat(consumeOperationState(operationId)).isEqualTo("consumed");
    }

    @Test
    void quotaUsageSettlementRecordsActualCostWithoutChangingPaidBalance() {
        String acct = UUID.randomUUID().toString();
        String operationId = UUID.randomUUID().toString();

        reserveUsage(acct, operationId, 300, 10_000, 88L).expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.source").isEqualTo("quota")
                .jsonPath("$.data.reservedCredits").isEqualTo(3)
                .jsonPath("$.data.balance").isEqualTo(0);
        settleUsage(acct, operationId, 500).expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.source").isEqualTo("quota")
                .jsonPath("$.data.actualCredits").isEqualTo(5)
                .jsonPath("$.data.adjustmentCredits").isEqualTo(0)
                .jsonPath("$.data.balance").isEqualTo(0);

        assertThat(balanceOf(acct)).isZero();
        assertThat(spentOf(acct)).isZero();
        assertThat(quotaUsed(acct)).isEqualTo(1);
        assertThat(usageAdjustmentCount(operationId)).isZero();
    }

    @Test
    void failedPricedUsageCompensationReturnsEntireReservation() {
        String acct = UUID.randomUUID().toString();
        String operationId = UUID.randomUUID().toString();
        award(acct, 5);

        reserveUsage(acct, operationId, 300, null, null).expectStatus().isOk();
        internalCompensate(acct, "ai_run_text", operationId).expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.action").isEqualTo("refunded")
                .jsonPath("$.data.balance").isEqualTo(5);
        internalCompensate(acct, "ai_run_text", operationId).expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.action").isEqualTo("deduplicated")
                .jsonPath("$.data.balance").isEqualTo(5);

        assertThat(balanceOf(acct)).isEqualTo(5);
        assertThat(spentOf(acct)).isZero();
        assertThat(consumeOperationState(operationId)).isEqualTo("compensated");
    }

    @Test
    void aiQuotaUsesEntitlementBeforePaidBalanceAndRecordsSource() {
        String acct = UUID.randomUUID().toString();

        entitledConsume(acct, "quota-1-" + acct, 10_000, 7).expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.source").isEqualTo("quota")
                .jsonPath("$.data.policyVersion").isEqualTo(7)
                .jsonPath("$.data.quotaLimit").isEqualTo(2)
                .jsonPath("$.data.balance").isEqualTo(0);
        entitledConsume(acct, "quota-2-" + acct, 10_000, 7).expectStatus().isOk()
                .expectBody().jsonPath("$.data.source").isEqualTo("quota");
        entitledConsume(acct, "quota-3-" + acct, 10_000, 7).expectStatus().isEqualTo(402);

        assertThat(balanceOf(acct)).isZero();
        assertThat(quotaUsed(acct)).isEqualTo(2);
        assertThat(quotaTxnCount(acct)).isEqualTo(2);
    }

    @Test
    void aiQuotaRequiresIntelligenceServiceAssertion() {
        String acct = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of(
                "accountId", acct,
                "feature", "ai_run_text",
                "operationId", "untrusted-quota-" + acct,
                "aiQuotaMultiplierBps", 100_000,
                "policyVersion", 99);

        client().post().uri("/internal/credits/consume")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isUnauthorized();
        client().post().uri("/internal/credits/consume")
                .header("X-Grassland-Identity", signService(null, "marketplace"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isForbidden();

        assertThat(quotaUsed(acct)).isZero();
    }

    @Test
    void paidConsumeAlsoRequiresIntelligenceServiceAssertion() {
        String acct = UUID.randomUUID().toString();
        award(acct, 1);
        Map<String, Object> body = Map.of(
                "accountId", acct,
                "feature", "comedy_generation",
                "operationId", "untrusted-paid-" + acct);

        client().post().uri("/internal/credits/consume")
                .header("X-Grassland-Identity", signService(null, "marketplace"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isForbidden();

        assertThat(balanceOf(acct)).isEqualTo(1);
        assertThat(txnCount(acct)).isEqualTo(1);
    }

    @Test
    void consumeCompensationRequiresIntelligenceServiceAssertion() {
        String acct = UUID.randomUUID().toString();
        String operationId = "protected-compensation-" + acct;
        entitledConsume(acct, operationId, 10_000, 32).expectStatus().isOk();
        Map<String, Object> body = Map.of(
                "accountId", acct,
                "feature", "ai_run_text",
                "consumeOperationId", operationId,
                "note", "failed");

        client().post().uri("/internal/credits/consume-compensations")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isUnauthorized();
        client().post().uri("/internal/credits/consume-compensations")
                .header("X-Grassland-Identity", signService(null, "trust"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isForbidden();

        assertThat(quotaUsed(acct)).isEqualTo(1);
        internalCompensate(acct, "ai_run_text", operationId).expectStatus().isOk();
        assertThat(quotaUsed(acct)).isZero();
    }

    @Test
    void aiQuotaRejectsNonAiFeatureEvenFromIntelligence() {
        String acct = UUID.randomUUID().toString();

        client().post().uri("/internal/credits/consume")
                .header("X-Grassland-Identity", signService(null, "intelligence"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "accountId", acct,
                        "feature", "admin_adjustment",
                        "operationId", "non-ai-feature-" + acct,
                        "aiQuotaMultiplierBps", 10_000,
                        "policyVersion", 1))
                .exchange().expectStatus().isBadRequest();

        assertThat(quotaUsed(acct)).isZero();
    }

    @Test
    void momentsGenerationSupportsAiQuotaEntitlement() {
        // 朋友圈内容生成（PRD §4.4）：intelligence 的 FinanceCreditsClient 必带权益快照字段扣减，
        // finance 白名单必须包含 moments_generation，否则整条链路 400（浏览器实测抓到的跨服务契约缺陷）。
        String acct = UUID.randomUUID().toString();
        award(acct, 1);

        client().post().uri("/internal/credits/consume")
                .header("X-Grassland-Identity", signService(null, "intelligence"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "accountId", acct,
                        "feature", "moments_generation",
                        "operationId", "moments-quota-" + acct,
                        "aiQuotaMultiplierBps", 10_000,
                        "policyVersion", 1))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.source").isNotEmpty();
    }

    @Test
    void aiQuotaMultiplierFloorsLimitAndThenFallsBackToPaidBalance() {
        String acct = UUID.randomUUID().toString();
        award(acct, 1);

        for (int index = 0; index < 3; index++) {
            entitledConsume(acct, "boosted-" + index + "-" + acct, 15_000, 9)
                    .expectStatus().isOk().expectBody()
                    .jsonPath("$.data.source").isEqualTo("quota")
                    .jsonPath("$.data.quotaLimit").isEqualTo(3);
        }
        entitledConsume(acct, "paid-fallback-" + acct, 15_000, 9)
                .expectStatus().isOk().expectBody()
                .jsonPath("$.data.source").isEqualTo("paid")
                .jsonPath("$.data.balance").isEqualTo(0);
    }

    @Test
    void quotaRefundReturnsOriginalDailyQuotaWithoutMintingPaidCredits() {
        String acct = UUID.randomUUID().toString();
        String operationId = "quota-refund-" + acct;
        entitledConsume(acct, operationId, 10_000, 11).expectStatus().isOk();

        internalCompensate(acct, "ai_run_text", operationId).expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.source").isEqualTo("quota")
                .jsonPath("$.data.action").isEqualTo("refunded")
                .jsonPath("$.data.balance").isEqualTo(0);

        assertThat(balanceOf(acct)).isZero();
        assertThat(quotaUsed(acct)).isZero();
        assertThat(quotaTxnCount(acct)).isEqualTo(2);
        entitledConsume(acct, "quota-reused-" + acct, 10_000, 11).expectStatus().isOk()
                .expectBody().jsonPath("$.data.source").isEqualTo("quota");
    }

    @Test
    void quotaCompensationSupportsMaximumLengthConsumeOperationId() {
        String acct = UUID.randomUUID().toString();
        String operationId = "x".repeat(256);

        entitledConsume(acct, operationId, 10_000, 11).expectStatus().isOk();
        internalCompensate(acct, "ai_run_text", operationId).expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.source").isEqualTo("quota")
                .jsonPath("$.data.action").isEqualTo("refunded");

        assertThat(quotaUsed(acct)).isZero();
        String refundOperationId = db.sql("""
                        SELECT operation_id
                        FROM credits_quota_transaction
                        WHERE account_id = CAST(:accountId AS uuid) AND type = 'refund'
                        """)
                .bind("accountId", acct)
                .map(row -> row.get("operation_id", String.class)).one().block();
        assertThat(refundOperationId).isEqualTo("refund:" + operationId).hasSize(263);
    }

    @Test
    void entitledConsumeIsIdempotentAndRejectsPolicyScopeMismatch() {
        String acct = UUID.randomUUID().toString();
        String operationId = "quota-idempotent-" + acct;

        entitledConsume(acct, operationId, 15_000, 12).expectStatus().isOk()
                .expectBody().jsonPath("$.data.deduplicated").isEqualTo(false);
        entitledConsume(acct, operationId, 15_000, 12).expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.deduplicated").isEqualTo(true)
                .jsonPath("$.data.source").isEqualTo("quota");
        entitledConsume(acct, operationId, 10_000, 13).expectStatus().isEqualTo(409);

        assertThat(quotaUsed(acct)).isEqualTo(1);
        assertThat(quotaTxnCount(acct)).isEqualTo(1);
    }

    @Test
    void concurrentEntitledConsumesCannotOverspendDailyQuota() {
        String acct = UUID.randomUUID().toString();
        WebClient webClient = WebClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-Grassland-Identity", signService(null, "intelligence"))
                .build();

        java.util.List<Integer> statuses = reactor.core.publisher.Flux.range(0, 3)
                .flatMap(index -> webClient.post().uri("/internal/credits/consume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of(
                                "accountId", acct,
                                "feature", "ai_run_text",
                                "operationId", "quota-race-" + index + "-" + acct,
                                "aiQuotaMultiplierBps", 10_000,
                                "policyVersion", 14))
                        .exchangeToMono(response -> Mono.just(response.statusCode().value())), 3)
                .collectList().block();

        assertThat(statuses).containsExactlyInAnyOrder(200, 200, 402);
        assertThat(quotaUsed(acct)).isEqualTo(2);
        assertThat(quotaTxnCount(acct)).isEqualTo(2);
        assertThat(balanceOf(acct)).isZero();
    }

    @Test
    void entitledConsumeStrictlyValidatesQuotaSnapshot() {
        String acct = UUID.randomUUID().toString();

        entitledConsume(acct, "bad-low-" + acct, 999, 1).expectStatus().isBadRequest();
        entitledConsume(acct, "bad-high-" + acct, 100_001, 1).expectStatus().isBadRequest();
        entitledConsume(acct, "bad-version-" + acct, 10_000, 0).expectStatus().isBadRequest();
        client().post().uri("/internal/credits/consume")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "accountId", acct,
                        "feature", "ai_run_text",
                        "operationId", "missing-version-" + acct,
                        "aiQuotaMultiplierBps", 10_000))
                .exchange().expectStatus().isBadRequest();
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

        // 公共读经用户断言（任务书 #87：统一 success/data 信封）
        client().get().uri("/api/credits/balance")
                .header("X-Grassland-Identity", sign(acct, "merchant", null, null))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.balance").isEqualTo(4)
                .jsonPath("$.data.totalEarned").isEqualTo(5)
                .jsonPath("$.data.totalSpent").isEqualTo(1);

        client().get().uri("/api/credits/history")
                .header("X-Grassland-Identity", sign(acct, "merchant", null, null))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.history[0].type").isEqualTo("consume")
                .jsonPath("$.data.history[0].feature").isEqualTo("comedy_generation")
                .jsonPath("$.data.history[0].amount").isEqualTo(-1)
                .jsonPath("$.data.history[1].type").isEqualTo("reward");
    }

    @Test
    void publicReadReturnsUnifiedEnvelopeContract() {
        // 未建户账号：balance 三字段全 0、history 空数组（纯读不建行，§5.4 不变量）
        String unknown = UUID.randomUUID().toString();
        client().get().uri("/api/credits/balance")
                .header("X-Grassland-Identity", sign(unknown, "recommender", null, null))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.balance").isEqualTo(0)
                .jsonPath("$.data.totalEarned").isEqualTo(0)
                .jsonPath("$.data.totalSpent").isEqualTo(0);

        client().get().uri("/api/credits/history")
                .header("X-Grassland-Identity", sign(unknown, "recommender", null, null))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.history").isArray()
                .jsonPath("$.data.history").isEmpty();
    }

    @Test
    void publicReadRejectsMissingAssertion() {
        // 无 X-Grassland-Identity 直连 → 401 信封，无余额/流水数据泄漏
        client().get().uri("/api/credits/balance").exchange().expectStatus().isUnauthorized().expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error").isNotEmpty()
                .jsonPath("$.data").doesNotExist();
        client().get().uri("/api/credits/history").exchange().expectStatus().isUnauthorized().expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error").isNotEmpty()
                .jsonPath("$.data").doesNotExist();
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
                .header("X-Grassland-Identity", signService(null, "intelligence"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountId", acct, "amount", 1, "feature", "comedy_generation",
                        "operationId", "refund:consume-" + acct, "note", "上游失败自动退回"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.balance").isEqualTo(5);

        // 重复退款 → deduplicated，余额不再叠加
        client().post().uri("/internal/credits/refund")
                .header("X-Grassland-Identity", signService(null, "intelligence"))
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
                .header("X-Grassland-Identity", signService(null, "identity"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountId", acct, "amount", 1, "feature", "comedy_generation",
                        "operationId", "X-" + acct, "note", "误用"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.deduplicated").isEqualTo(true);
        assertThat(balanceOf(acct)).isEqualTo(1);   // 未退款
    }

    @Test
    void compensationAfterConsumeRefundsExactlyOnce() {
        String acct = UUID.randomUUID().toString();
        String operationId = "consume-before-compensate-" + acct;
        award(acct, 2);
        consume(acct, "ai_run_text", operationId);

        internalCompensate(acct, "ai_run_text", operationId).expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.action").isEqualTo("refunded")
                .jsonPath("$.data.balance").isEqualTo(2);
        internalCompensate(acct, "ai_run_text", operationId).expectStatus().isOk()
                .expectBody().jsonPath("$.data.action").isEqualTo("deduplicated");

        assertThat(balanceOf(acct)).isEqualTo(2);
        assertThat(txnCount(acct)).isEqualTo(3); // award + consume + one refund
    }

    @Test
    void legacyAiRefundRequiresIntelligenceAssertionBeforeCompensation() {
        String acct = UUID.randomUUID().toString();
        String operationId = "protected-refund-" + acct;
        award(acct, 2);
        consume(acct, "ai_run_text", operationId);

        Map<String, Object> body = Map.of(
                "accountId", acct,
                "amount", 1,
                "feature", "ai_run_text",
                "operationId", "refund:" + operationId,
                "note", "untrusted legacy caller");
        client().post().uri("/internal/credits/refund")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isUnauthorized();
        client().post().uri("/internal/credits/refund")
                .header("X-Grassland-Identity", signService(null, "marketplace"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isForbidden();

        assertThat(balanceOf(acct)).isEqualTo(1);
        assertThat(txnCount(acct)).isEqualTo(2);
    }

    @Test
    void refundAuthorizationDoesNotDependOnOperationIdPrefix() {
        String acct = UUID.randomUUID().toString();
        award(acct, 2);
        Map<String, Object> body = Map.of(
                "accountId", acct,
                "amount", 1,
                "feature", "admin_adjust",
                "operationId", "not-a-refund-prefix-" + acct,
                "note", "untrusted adjustment");

        client().post().uri("/internal/credits/refund")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isUnauthorized();
        client().post().uri("/internal/credits/refund")
                .header("X-Grassland-Identity", signService(null, "marketplace"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isForbidden();
        client().post().uri("/internal/credits/refund")
                .header("X-Grassland-Identity", signService(null, "intelligence"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isForbidden();
        client().post().uri("/internal/credits/refund")
                .header("X-Grassland-Identity", signService(null, "identity"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isOk();

        assertThat(balanceOf(acct)).isEqualTo(3);
    }

    @Test
    void awardRequiresIdentityServiceAssertion() {
        String acct = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of(
                "accountId", acct, "amount", 3, "note", "admin grant");

        client().post().uri("/internal/credits/award")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isUnauthorized();
        client().post().uri("/internal/credits/award")
                .header("X-Grassland-Identity", signService(null, "intelligence"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isForbidden();
        client().post().uri("/internal/credits/award")
                .header("X-Grassland-Identity", signService(null, "identity"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isOk();

        assertThat(balanceOf(acct)).isEqualTo(3);
    }

    @Test
    void compensationBeforeConsumeFencesLateCharge() {
        String acct = UUID.randomUUID().toString();
        String operationId = "compensate-before-consume-" + acct;
        award(acct, 2);

        internalCompensate(acct, "ai_run_text", operationId).expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.action").isEqualTo("fenced")
                .jsonPath("$.data.balance").isEqualTo(2);
        internalConsume(acct, "ai_run_text", operationId).expectStatus().isEqualTo(409);

        assertThat(balanceOf(acct)).isEqualTo(2);
        assertThat(txnCount(acct)).isEqualTo(1); // award only
    }

    @Test
    void compensationRejectsOperationScopeMismatch() {
        String acct = UUID.randomUUID().toString();
        String other = UUID.randomUUID().toString();
        String operationId = "scope-" + acct;
        award(acct, 1);
        consume(acct, "ai_run_text", operationId);

        internalCompensate(other, "ai_run_text", operationId)
                .expectStatus().isEqualTo(409);
        assertThat(balanceOf(acct)).isZero();
    }

    @Test
    void compensationRejectsMalformedOrOversizedInput() {
        String acct = UUID.randomUUID().toString();

        internalCompensate("not-a-uuid", "ai_run_text", "operation")
                .expectStatus().isBadRequest();
        internalCompensate(acct, "f".repeat(65), "operation")
                .expectStatus().isBadRequest();
        internalCompensate(acct, "ai_run_text", "o".repeat(257))
                .expectStatus().isBadRequest();
        client().post().uri("/internal/credits/consume-compensations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "accountId", acct,
                        "feature", "ai_run_text",
                        "consumeOperationId", "operation",
                        "note", "n".repeat(513)))
                .exchange().expectStatus().isBadRequest();

        assertThat(txnCount(acct)).isZero();
    }

    @Test
    void concurrentConsumeAndCompensationNeverLoseOrMintCredit() {
        String acct = UUID.randomUUID().toString();
        String operationId = "race-" + acct;
        award(acct, 2);
        WebClient webClient = WebClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-Grassland-Identity", signService(null, "intelligence"))
                .build();

        var statuses = reactor.core.publisher.Mono.zip(
                        webClient.post().uri("/internal/credits/consume")
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(Map.of("accountId", acct, "feature", "ai_run_text",
                                        "operationId", operationId))
                                .exchangeToMono(response -> Mono.just(response.statusCode().value())),
                        webClient.post().uri("/internal/credits/consume-compensations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(Map.of("accountId", acct, "feature", "ai_run_text",
                                        "consumeOperationId", operationId, "note", "race"))
                                .exchangeToMono(response -> Mono.just(response.statusCode().value())))
                .block();

        assertThat(statuses).isNotNull();
        assertThat(statuses.getT2()).isEqualTo(200);
        assertThat(statuses.getT1()).isIn(200, 409);
        assertThat(balanceOf(acct)).isEqualTo(2);
        assertThat(txnCount(acct)).isIn(1L, 3L);
    }

    @Test
    void fencedCompensationReconcilesRefundFromOldClientWithoutDoubleCredit() {
        String acct = UUID.randomUUID().toString();
        String operationId = "rolling-upgrade-" + acct;
        award(acct, 2);
        consume(acct, "ai_run_text", operationId);
        client().post().uri("/internal/credits/refund")
                .header("X-Grassland-Identity", signService(null, "intelligence"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountId", acct, "amount", 1, "feature", "ai_run_text",
                        "operationId", "refund:" + operationId, "note", "old client"))
                .exchange().expectStatus().isOk();

        internalCompensate(acct, "ai_run_text", operationId).expectStatus().isOk()
                .expectBody().jsonPath("$.data.action").isEqualTo("deduplicated");

        assertThat(balanceOf(acct)).isEqualTo(2);
        assertThat(txnCount(acct)).isEqualTo(3);
    }

    @Test
    void compensationRefundsConsumeWrittenByOldFinanceInstanceAfterMigration() {
        String acct = UUID.randomUUID().toString();
        String operationId = "old-finance-consume-" + acct;
        award(acct, 2);
        oldFinanceConsume(acct, "ai_run_text", operationId).block();

        internalCompensate(acct, "ai_run_text", operationId).expectStatus().isOk()
                .expectBody().jsonPath("$.data.action").isEqualTo("refunded");

        assertThat(balanceOf(acct)).isEqualTo(2);
        assertThat(txnCount(acct)).isEqualTo(3);
    }

    @Test
    void compensationFenceRejectsLateConsumeFromOldFinanceInstance() {
        String acct = UUID.randomUUID().toString();
        String operationId = "old-finance-late-" + acct;
        award(acct, 2);
        internalCompensate(acct, "ai_run_text", operationId).expectStatus().isOk();

        assertThatThrownBy(() -> oldFinanceConsume(acct, "ai_run_text", operationId).block())
                .isInstanceOf(RuntimeException.class);

        assertThat(balanceOf(acct)).isEqualTo(2);
        assertThat(txnCount(acct)).isEqualTo(1);
    }

    @Test
    void quotaConsumeFencesSameOperationFromOldFinanceInstance() {
        String acct = UUID.randomUUID().toString();
        String operationId = "quota-before-old-finance-" + acct;
        award(acct, 1);
        entitledConsume(acct, operationId, 10_000, 31).expectStatus().isOk()
                .expectBody().jsonPath("$.data.source").isEqualTo("quota");

        assertThatThrownBy(() -> oldFinanceConsume(acct, "ai_run_text", operationId).block())
                .isInstanceOf(RuntimeException.class);

        assertThat(balanceOf(acct)).isEqualTo(1);
        assertThat(quotaUsed(acct)).isEqualTo(1);
        assertThat(txnCount(acct)).isEqualTo(1); // award only; no paid consume
    }

    @Test
    void internalEndpointsRequireServiceAssertionAndRejectForwardedRequests() {
        String acct = UUID.randomUUID().toString();
        // 无服务断言 → 401
        client().post().uri("/internal/credits/consume")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountId", acct, "feature", "comedy_generation", "operationId", "op"))
                .exchange().expectStatus().isUnauthorized();
        // 经代理（带 X-Forwarded-For）→ 404（rejectForwarded 纵深防御）
        client().post().uri("/internal/credits/consume")
                .header("X-Forwarded-For", "10.0.0.1")
                .header("X-Grassland-Identity", signService(null, "intelligence"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountId", acct, "feature", "comedy_generation", "operationId", "op"))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void publicReadsRequireUserAssertion() {
        // 无断言 → 401（credits 识人完全靠断言）
        client().get().uri("/api/credits/balance").exchange().expectStatus().isUnauthorized();
        // 断言指向自己 → 只读到自己余额（默认 0；任务书 #87 信封形态）
        String acct = UUID.randomUUID().toString();
        client().get().uri("/api/credits/balance")
                .header("X-Grassland-Identity", sign(acct, "merchant", null, null))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.balance").isEqualTo(0);
    }

    @Test
    void internalBalancesReturnsAllAccountsWithCredits() {
        String acctA = UUID.randomUUID().toString();
        String acctB = UUID.randomUUID().toString();
        award(acctA, 7);
        award(acctB, 3);

        client().post().uri("/internal/credits/balances")
                .header("X-Grassland-Identity", signService(null, "identity"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountIds", java.util.List.of(acctA, acctB)))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.accounts.length()").isEqualTo(2);
    }

    @Test
    void internalBalancesOmitsAccountsWithoutCredits() {
        String withCredits = UUID.randomUUID().toString();
        String noCredits = UUID.randomUUID().toString();
        award(withCredits, 5);

        // 未建户的 accountId 不在结果里（调用方按缺失 = 0 余额处理）
        client().post().uri("/internal/credits/balances")
                .header("X-Grassland-Identity", signService(null, "identity"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountIds", java.util.List.of(withCredits, noCredits)))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.accounts.length()").isEqualTo(1)
                .jsonPath("$.data.accounts[0].accountId").isEqualTo(withCredits)
                .jsonPath("$.data.accounts[0].balance").isEqualTo(5);
    }

    @Test
    void internalBalancesAcceptsEmptyListAndMissingAccounts() {
        client().post().uri("/internal/credits/balances")
                .header("X-Grassland-Identity", signService(null, "identity"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountIds", java.util.List.of()))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.accounts.length()").isEqualTo(0);
    }

    @Test
    void internalBalancesRequiresIdentityServiceAssertion() {
        client().post().uri("/internal/credits/balances")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountIds", java.util.List.of(UUID.randomUUID().toString())))
                .exchange().expectStatus().isUnauthorized();
        client().post().uri("/internal/credits/balances")
                .header("X-Grassland-Identity", signService(null, "intelligence"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountIds", java.util.List.of(UUID.randomUUID().toString())))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void consumeOperationQueryReturnsFinanceAuthorityWithoutBalances() {
        String paidAccount = UUID.randomUUID().toString();
        String quotaAccount = UUID.randomUUID().toString();
        String paidOperation = "reconcile-paid-" + paidAccount;
        String quotaOperation = "reconcile-quota-" + quotaAccount;
        award(paidAccount, 1);
        consume(paidAccount, "video_production_video", paidOperation);
        entitledConsume(quotaAccount, quotaOperation, 10_000, 42).expectStatus().isOk();
        internalCompensate(paidAccount, "video_production_video", paidOperation)
                .expectStatus().isOk();

        client().post().uri("/internal/credits/consume-operations/query")
                .header("X-Grassland-Identity", signService(null, "intelligence"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("operationIds", java.util.List.of(
                        paidOperation, quotaOperation, "missing-operation")))
                .exchange().expectStatus().isOk().expectBody(String.class)
                .value(body -> assertThat(body)
                        .contains(paidOperation, quotaOperation)
                        .contains("\"state\":\"compensated\"")
                        .contains("\"source\":\"paid\"")
                        .contains("\"state\":\"consumed\"")
                        .contains("\"source\":\"quota\"")
                        .contains("\"policyVersion\":42")
                        .doesNotContain("missing-operation")
                        .doesNotContain("balance"));
    }

    @Test
    void consumeOperationQueryRequiresIntelligenceAndBoundsInput() {
        Map<String, Object> body = Map.of("operationIds", java.util.List.of("operation"));
        client().post().uri("/internal/credits/consume-operations/query")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isUnauthorized();
        client().post().uri("/internal/credits/consume-operations/query")
                .header("X-Grassland-Identity", signService(null, "identity"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isForbidden();
        client().post().uri("/internal/credits/consume-operations/query")
                .header("X-Grassland-Identity", signService(null, "intelligence"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("operationIds", java.util.Collections.nCopies(501, "operation")))
                .exchange().expectStatus().isBadRequest();
    }

    // ---------- helpers ----------

    private void award(String acct, int amount) {
        client().post().uri("/internal/credits/award")
                .header("X-Grassland-Identity", signService(null, "identity"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountId", acct, "amount", amount, "note", "test grant"))
                .exchange().expectStatus().isOk();
    }

    private void consume(String acct, String feature, String operationId) {
        internalConsume(acct, feature, operationId).expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec internalConsume(String acct, String feature, String operationId) {
        return client().post().uri("/internal/credits/consume")
                .header("X-Grassland-Identity", signService(null, "intelligence"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountId", acct, "feature", feature, "operationId", operationId))
                .exchange();
    }

    private WebTestClient.ResponseSpec entitledConsume(
            String acct, String operationId, int multiplierBps, long policyVersion) {
        return client().post().uri("/internal/credits/consume")
                .header("X-Grassland-Identity", signService(null, "intelligence"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "accountId", acct,
                        "feature", "ai_run_text",
                        "operationId", operationId,
                        "aiQuotaMultiplierBps", multiplierBps,
                        "policyVersion", policyVersion))
                .exchange();
    }

    private WebTestClient.ResponseSpec internalCompensate(String acct, String feature, String operationId) {
        return client().post().uri("/internal/credits/consume-compensations")
                .header("X-Grassland-Identity", signService(null, "intelligence"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountId", acct, "feature", feature,
                        "consumeOperationId", operationId, "note", "AI run failed"))
                .exchange();
    }

    private WebTestClient.ResponseSpec reserveUsage(
            String acct, String operationId, long estimatedCents,
            Integer multiplierBps, Long entitlementPolicyVersion) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("accountId", acct);
        body.put("feature", "ai_run_text");
        body.put("operationId", operationId);
        body.put("estimatedCents", estimatedCents);
        body.put("creditsCentsPolicyVersion", "test-v1");
        if (multiplierBps != null) {
            body.put("aiQuotaMultiplierBps", multiplierBps);
            body.put("policyVersion", entitlementPolicyVersion);
        }
        return client().post().uri("/internal/credits/usage-reservations")
                .header("X-Grassland-Identity", signService(null, "intelligence"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private WebTestClient.ResponseSpec settleUsage(String acct, String operationId, long actualCents) {
        return client().post().uri("/internal/credits/usage-settlements")
                .header("X-Grassland-Identity", signService(null, "intelligence"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "accountId", acct,
                        "feature", "ai_run_text",
                        "consumeOperationId", operationId,
                        "actualCents", actualCents,
                        "creditsCentsPolicyVersion", "test-v1"))
                .exchange();
    }

    private Mono<Void> oldFinanceConsume(String acct, String feature, String operationId) {
        Mono<Void> mutation = db.sql("""
                        UPDATE credits_account
                        SET balance = balance - 1, total_spent = total_spent + 1, updated_at = now()
                        WHERE account_id = CAST(:accountId AS uuid) AND balance >= 1
                        RETURNING balance
                        """)
                .bind("accountId", acct)
                .map((row, meta) -> row.get("balance", Integer.class))
                .one()
                .flatMap(balance -> db.sql("""
                                INSERT INTO credits_transaction(
                                    id, account_id, amount, balance_after, type, feature, operation_id)
                                VALUES (CAST(:id AS uuid), CAST(:accountId AS uuid), -1, :balance,
                                        'consume', :feature, :operationId)
                                """)
                        .bind("id", UUID.randomUUID().toString())
                        .bind("accountId", acct)
                        .bind("balance", balance)
                        .bind("feature", feature)
                        .bind("operationId", operationId)
                        .then());
        return transactions.transactional(mutation);
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

    private long usageAdjustmentCount(String operationId) {
        Integer count = db.sql("""
                        SELECT COUNT(*)::int AS count
                        FROM credits_transaction
                        WHERE operation_id = :operationId AND type = 'usage_adjustment'
                        """)
                .bind("operationId", "settle:" + operationId)
                .map(row -> row.get("count", Integer.class)).one().block();
        return count == null ? 0 : count.longValue();
    }

    private String consumeOperationState(String operationId) {
        return db.sql("SELECT state FROM credits_consume_operation WHERE operation_id = :operationId")
                .bind("operationId", operationId)
                .map(row -> row.get("state", String.class)).one().block();
    }

    private int quotaUsed(String acct) {
        Integer value = db.sql("""
                        SELECT COALESCE(SUM(used), 0)::int AS used
                        FROM credits_daily_quota_usage
                        WHERE account_id = CAST(:accountId AS uuid)
                        """)
                .bind("accountId", acct)
                .map(row -> row.get("used", Integer.class)).one().block();
        return value == null ? 0 : value;
    }

    private long quotaTxnCount(String acct) {
        Integer value = db.sql("""
                        SELECT COUNT(*)::int AS count
                        FROM credits_quota_transaction
                        WHERE account_id = CAST(:accountId AS uuid)
                        """)
                .bind("accountId", acct)
                .map(row -> row.get("count", Integer.class)).one().block();
        return value == null ? 0 : value.longValue();
    }
}
