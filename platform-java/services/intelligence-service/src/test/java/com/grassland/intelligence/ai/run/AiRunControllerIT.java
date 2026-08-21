package com.grassland.intelligence.ai.run;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.DnsPinningResolver;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 控制面 Run 执行闭环（GL-P3-AI-001）：平台 run（扣分/结算/事件）、平台 run 失败退款、
 * BYOK run（解密→调用链，不扣分）、BYOK 回退未授权拒绝、预算超限拒绝、TaskContext 快照。
 *
 * <p>CREDITS WireMock 托管 legacy 积分端点；QWEN WireMock 托管 provider（平台 + BYOK 共用）。
 * KEK 注入使 BYOK 密钥创建 + 运行时解密可用（与 {@code AiProviderKeyControllerIT} 同值）。
 */
@DisplayName("AiRunController (控制面执行闭环)")
@Import(AiRunControllerIT.DnsTestConfiguration.class)
class AiRunControllerIT extends IntelligenceItSupport {

    private static final String TEST_KEK_BASE64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String ACCOUNT = "33333333-3333-3333-3333-333333333333";
    private static final String BYOK_ACCOUNT = "44444444-4444-4444-4444-444444444444";
    private static final String ORG = "org-5001";
    private static final String ORG_ACCOUNT = "55555555-5555-5555-5555-555555555555";

    static final WireMockServer CREDITS = new WireMockServer(0);
    static {
        CREDITS.start();
    }

    @Autowired
    EnvelopeEncryption encryption;

    @Autowired
    DnsPinningResolver dnsPinningResolver;

    @MockitoSpyBean
    ModelBudgetService budgetService;

    @MockitoSpyBean
    TextCompletionClient textClient;

    @MockitoSpyBean
    AiRunRepository runRepository;

    @MockitoSpyBean
    CreditsClient creditsClient;

    @Autowired
    AiRunController runController;

    @DynamicPropertySource
    static void extraProps(DynamicPropertyRegistry r) {
        r.add("crypto.kek.encoded", () -> TEST_KEK_BASE64);
        // CreditsClient 是 FinanceCreditsClient（GL-P3-AI-001）；把它指向同一个 WireMock，
        // 使 consume/refund 走生产路径打到桩端点。
        r.add("credits.finance.base-url", CREDITS::baseUrl);
        r.add("marketplace.service.base-url", CREDITS::baseUrl);
    }

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM intelligence_outbox").then().block();
        db.sql("DELETE FROM ai_credit_compensation").then().block();
        db.sql("DELETE FROM ai_run").then().block();
        db.sql("DELETE FROM creation_context_snapshot").then().block();
        db.sql("DELETE FROM ai_provider_key").then().block();
        db.sql("DELETE FROM ai_org_byok_policy").then().block();
        db.sql("DELETE FROM ai_model_budget").then().block();
        db.sql("DELETE FROM platform_model_concurrency_slot").then().block();
        db.sql("DELETE FROM platform_model_config").then().block();
        // 自种子平台 text/primary 配置指向 QWEN（不依赖启动期 seeder，保证跨测试类隔离）
        String platformConfigId = db.sql("INSERT INTO platform_model_config(capability, model_role, provider, model, "
                        + "base_url, max_concurrency, health_status, enabled, version) "
                        + "VALUES ('text','primary','qwen','qwen-plus',:baseUrl,1,'healthy',true,1) RETURNING id::text")
                .bind("baseUrl", QWEN.baseUrl())
                .map((row, meta) -> row.get("id", String.class)).one().block();
        db.sql("INSERT INTO platform_model_concurrency_slot(config_id, slot_no) VALUES (CAST(:id AS uuid), 1)")
                .bind("id", platformConfigId).then().block();
        CREDITS.resetAll();
        QWEN.resetRequests();
        stubCreditsOk();
    }

    @Test
    @DisplayName("平台 run：QWEN 返回 → completed 落 usage/actualCents；consume 扣分；outbox AiRunCompleted")
    void platformRunSucceeds() {
        stubQwenOk();
        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"写一句问候","maxTokens":128,"allowFallback":true}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content").isEqualTo("hello")
                .jsonPath("$.status").isEqualTo("completed")
                .jsonPath("$.inputTokens").isEqualTo(10)
                .jsonPath("$.outputTokens").isEqualTo(5)
                .jsonPath("$.actualCents").isEqualTo(2)  // qwen-plus: (10*3+999)/1000 + (5*6+999)/1000
                .jsonPath("$.taskContext.priceTableVersion").isEqualTo("v1")
                .jsonPath("$.taskContext.platformModelVersion").isEqualTo(1)
                .jsonPath("$.taskContext.resolutionType").isEqualTo("PLATFORM")
                .jsonPath("$.runId").isNotEmpty();

        // ai_run 落 usage 计量
        Long completed = db.sql("SELECT COUNT(*) AS n FROM ai_run WHERE status='completed' AND input_tokens=10 AND output_tokens=5")
                .map((r, m) -> r.get("n", Long.class)).one().block();
        assertThat(completed).isEqualTo(1);
        String operationId = db.sql("SELECT operation_id::text AS operation_id FROM ai_run LIMIT 1")
                .map((row, meta) -> row.get("operation_id", String.class)).one().block();
        // 平台扣分一次
        CREDITS.verify(1, postRequestedFor(urlEqualTo("/internal/credits/consume"))
                .withRequestBody(matchingJsonPath("$.operationId", equalTo(operationId))));
        CREDITS.verify(0, postRequestedFor(urlEqualTo("/internal/credits/refund")));
        // outbox AiRunCompleted
        Long events = db.sql("SELECT COUNT(*) AS n FROM intelligence_outbox WHERE event_type='AiRunCompleted'")
                .map((r, m) -> r.get("n", Long.class)).one().block();
        assertThat(events).isEqualTo(1);
    }

    @Test
    @DisplayName("平台 run 失败：provider 500 → failed + 退款；outbox AiRunFailed")
    void platformRunFailureRefunds() {
        QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse().withStatus(500)));

        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"x","maxTokens":64,"allowFallback":true}
                        """)
                .exchange()
                .expectStatus().is5xxServerError();

        Long failed = db.sql("SELECT COUNT(*) AS n FROM ai_run WHERE status='failed'")
                .map((r, m) -> r.get("n", Long.class)).one().block();
        assertThat(failed).isEqualTo(1);
        String operationId = db.sql("SELECT operation_id::text AS operation_id FROM ai_run LIMIT 1")
                .map((row, meta) -> row.get("operation_id", String.class)).one().block();
        CREDITS.verify(1, postRequestedFor(urlEqualTo("/internal/credits/consume")));
        CREDITS.verify(1, postRequestedFor(urlEqualTo("/internal/credits/consume-compensations"))
                .withRequestBody(matchingJsonPath("$.consumeOperationId", equalTo(operationId))));
        CREDITS.verify(0, postRequestedFor(urlEqualTo("/internal/credits/refund")));
        Long events = db.sql("SELECT COUNT(*) AS n FROM intelligence_outbox WHERE event_type='AiRunFailed'")
                .map((r, m) -> r.get("n", Long.class)).one().block();
        assertThat(events).isEqualTo(1);
    }

    @Test
    @DisplayName("consume 响应丢失会持久化补偿意图并释放预算")
    void consumeResponseLossPersistsCompensationIntent() {
        db.sql("INSERT INTO ai_model_budget(organization_id, capability, provider, max_tokens_daily, enabled) "
                + "VALUES (:org, 'text', 'platform', 1000, true)").bind("org", ORG).then().block();
        CREDITS.stubFor(post(urlEqualTo("/internal/credits/consume"))
                .atPriority(1)
                .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));
        CREDITS.stubFor(post(urlEqualTo("/internal/credits/consume-compensations"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(500)));

        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", signWithOrg(ORG_ACCOUNT, ORG))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"x","maxTokens":16,"allowFallback":true}
                        """)
                .exchange().expectStatus().is5xxServerError();

        Long failed = db.sql("SELECT COUNT(*) AS n FROM ai_run WHERE status='failed'")
                .map((row, meta) -> row.get("n", Long.class)).one().block();
        Long pending = db.sql("SELECT COUNT(*) AS n FROM ai_credit_compensation "
                        + "WHERE status='pending' AND attempt_count=1 AND last_error_code IS NOT NULL")
                .map((row, meta) -> row.get("n", Long.class)).one().block();
        Long reserved = db.sql("SELECT current_daily_tokens FROM ai_model_budget WHERE organization_id=:org")
                .bind("org", ORG).map((row, meta) -> row.get("current_daily_tokens", Long.class)).one().block();
        assertThat(failed).isEqualTo(1);
        assertThat(pending).isEqualTo(1);
        assertThat(reserved).isZero();
        QWEN.verify(0, postRequestedFor(urlEqualTo("/chat/completions")));
    }

    @Test
    @DisplayName("HTTP 取消会收尾 Run、释放预算与并发 lease，但不退积分")
    void clientCancellationFinalizesRunAndReleasesBudget() {
        db.sql("INSERT INTO ai_model_budget(organization_id, capability, provider, max_tokens_daily, enabled) "
                + "VALUES (:org, 'text', 'platform', 1000, true)").bind("org", ORG).then().block();
        doReturn(Mono.never()).when(textClient)
                .complete(anyString(), anyString(), anyString(), anyString(), anyInt(), eq(false));
        var request = MockServerHttpRequest.post("/api/ai/runs")
                .header("X-Grassland-Identity", signWithOrg(ORG_ACCOUNT, ORG))
                .build();
        var exchange = MockServerWebExchange.from(request);

        var subscription = runController.execute(
                        new ExecuteRunRequest("text", "x", 16, true), exchange)
                .subscribe(ignored -> { }, ignored -> { });
        awaitRunStatus("running");
        verify(textClient, timeout(5000))
                .complete(anyString(), anyString(), anyString(), anyString(), anyInt(), eq(false));
        subscription.dispose();
        awaitRunStatus("cancelled");

        Long reserved = db.sql("SELECT current_daily_tokens FROM ai_model_budget WHERE organization_id=:org")
                .bind("org", ORG).map((row, meta) -> row.get("current_daily_tokens", Long.class)).one().block();
        Long cancelledEvents = db.sql("SELECT COUNT(*) AS n FROM intelligence_outbox WHERE event_type='AiRunCancelled'")
                .map((row, meta) -> row.get("n", Long.class)).one().block();
        Long leasedSlots = db.sql("SELECT COUNT(*) AS n FROM platform_model_concurrency_slot WHERE lease_token IS NOT NULL")
                .map((row, meta) -> row.get("n", Long.class)).one().block();
        assertThat(reserved).isZero();
        assertThat(cancelledEvents).isEqualTo(1);
        assertThat(leasedSlots).isZero();
        CREDITS.verify(1, postRequestedFor(urlEqualTo("/internal/credits/consume")));
        CREDITS.verify(0, postRequestedFor(urlEqualTo("/internal/credits/consume-compensations")));
        CREDITS.verify(0, postRequestedFor(urlEqualTo("/internal/credits/refund")));
    }

    @Test
    @DisplayName("扣分响应前 HTTP 取消会收尾 Run、释放预算并补偿未知扣费")
    void cancellationDuringCreditConsumeFinalizesPreparation() {
        db.sql("INSERT INTO ai_model_budget(organization_id, capability, provider, max_tokens_daily, enabled) "
                + "VALUES (:org, 'text', 'platform', 1000, true)").bind("org", ORG).then().block();
        doReturn(Mono.never()).when(creditsClient)
                .consume(eq(ORG_ACCOUNT), eq(CreditFeature.AI_RUN_TEXT), anyString());
        var request = MockServerHttpRequest.post("/api/ai/runs")
                .header("X-Grassland-Identity", signWithOrg(ORG_ACCOUNT, ORG))
                .build();
        var exchange = MockServerWebExchange.from(request);

        var subscription = runController.execute(
                        new ExecuteRunRequest("text", "x", 16, true), exchange)
                .subscribe(ignored -> { }, ignored -> { });
        awaitRunStatus("running");
        subscription.dispose();
        awaitRunStatus("cancelled");

        Long reserved = db.sql("SELECT current_daily_tokens FROM ai_model_budget WHERE organization_id=:org")
                .bind("org", ORG).map((row, meta) -> row.get("current_daily_tokens", Long.class)).one().block();
        Long cancelledEvents = db.sql("SELECT COUNT(*) AS n FROM intelligence_outbox WHERE event_type='AiRunCancelled'")
                .map((row, meta) -> row.get("n", Long.class)).one().block();
        assertThat(reserved).isZero();
        assertThat(cancelledEvents).isEqualTo(1);
        QWEN.verify(0, postRequestedFor(urlEqualTo("/chat/completions")));
        Long compensationIntents = db.sql("SELECT COUNT(*) AS n FROM ai_credit_compensation"
                        + " WHERE run_id = (SELECT id FROM ai_run ORDER BY started_at DESC LIMIT 1)"
                        + " AND status IN ('pending', 'completed')")
                .map((row, meta) -> row.get("n", Long.class)).one().block();
        assertThat(compensationIntents).isEqualTo(1);
        CREDITS.verify(0, postRequestedFor(urlEqualTo("/internal/credits/refund")));
    }

    @Test
    @DisplayName("Run 创建期间 HTTP 取消会回滚预算预留")
    void cancellationDuringRunCreationRollsBackReservation() {
        db.sql("INSERT INTO ai_model_budget(organization_id, capability, provider, max_tokens_daily, enabled) "
                + "VALUES (:org, 'text', 'platform', 1000, true)").bind("org", ORG).then().block();
        doReturn(Mono.never()).when(runRepository).create(any(AiRun.class));
        var request = MockServerHttpRequest.post("/api/ai/runs")
                .header("X-Grassland-Identity", signWithOrg(ORG_ACCOUNT, ORG))
                .build();
        var exchange = MockServerWebExchange.from(request);

        var subscription = runController.execute(
                        new ExecuteRunRequest("text", "x", 16, true), exchange)
                .subscribe(ignored -> { }, ignored -> { });
        verify(runRepository, timeout(5000)).create(any(AiRun.class));
        subscription.dispose();

        Long reserved = db.sql("SELECT current_daily_tokens FROM ai_model_budget WHERE organization_id=:org")
                .bind("org", ORG).map((row, meta) -> row.get("current_daily_tokens", Long.class)).one().block();
        assertThat(reserved).isZero();
        CREDITS.verify(0, postRequestedFor(urlEqualTo("/internal/credits/consume")));
        CREDITS.verify(0, postRequestedFor(urlEqualTo("/internal/credits/consume-compensations")));
    }

    @Test
    @DisplayName("BYOK run：解密密钥→调用 provider；actualCents=0；不扣平台积分")
    void byokRunDecryptsAndCalls() {
        doReturn(Mono.just(new TextCompletionResult("hello", 10, 5)))
                .when(textClient).complete(
                        eq("https://api.example.com"), eq("sk-test-byok-secret"), eq("byok-model"),
                        eq("x"), eq(32), eq(true));
        // 注册个人 BYOK 密钥；provider 调用通过 spy 验证解密后的 bearer 参数。
        client().post().uri("/api/ai/keys")
                .header("X-Grassland-Identity", sign(BYOK_ACCOUNT, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","provider":"openai-compatible","baseUrl":"https://api.example.com","model":"byok-model","apiKey":"sk-test-byok-secret"}
                        """)
                .exchange().expectStatus().isCreated();

        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", sign(BYOK_ACCOUNT, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"x","maxTokens":32}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content").isEqualTo("hello")
                .jsonPath("$.actualCents").isEqualTo(0)  // BYOK 不收平台 AI 费（D-11）
                .jsonPath("$.taskContext.resolutionType").isEqualTo("BYOK")
                .jsonPath("$.runId").isNotEmpty();

        // BYOK 不扣平台积分
        CREDITS.verify(0, postRequestedFor(urlEqualTo("/internal/credits/consume")));
        CREDITS.verify(0, postRequestedFor(urlEqualTo("/internal/credits/refund")));
    }

    @Test
    @DisplayName("组织 BYOK run：成员无个人密钥走组织密钥；actualCents=0；TaskContext 带组织 ID")
    void orgByokRunUsesOrgKeyForMember() {
        doReturn(Mono.just(new TextCompletionResult("org-hello", 10, 5)))
                .when(textClient).complete(
                        eq("https://api.example.com"), eq("sk-org-run-secret"), eq("org-byok-model"),
                        eq("x"), eq(32), eq(true));
        db.sql("""
                INSERT INTO ai_provider_key(organization_id, owner_account_id, capability, provider, base_url, model,
                    encrypted_key, key_version, masked_hint, enabled)
                VALUES (:org, :owner, 'text', 'openai-compatible', 'https://api.example.com', 'org-byok-model',
                    :encrypted, 'v1', 'sk-***org', true)
                """)
                .bind("org", ORG)
                .bind("owner", ORG_ACCOUNT)
                .bind("encrypted", encryption.encrypt("sk-org-run-secret"))
                .then().block();

        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", signWithOrg(ORG_ACCOUNT, ORG))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"x","maxTokens":32}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content").isEqualTo("org-hello")
                .jsonPath("$.actualCents").isEqualTo(0)
                .jsonPath("$.taskContext.resolutionType").isEqualTo("BYOK")
                .jsonPath("$.taskContext.byokOrganizationId").isEqualTo(ORG);

        String recorded = db.sql("SELECT byok_organization_id FROM ai_run WHERE account_id = :account")
                .bind("account", ORG_ACCOUNT)
                .map((r, m) -> r.get("byok_organization_id", String.class)).one().block();
        assertThat(recorded).isEqualTo(ORG);
        CREDITS.verify(0, postRequestedFor(urlEqualTo("/internal/credits/consume")));
    }

    @Test
    @DisplayName("组织配了密钥：策略未允许时 allowFallback=true 也拒绝；策略允许后才回退平台（D-11 双闸）")
    void orgFallbackRequiresPolicyEvenWhenRequestAllows() {
        stubQwenOk();
        // 组织只配 image_generation 密钥：text 能力两级未命中 → 策略介入
        db.sql("""
                INSERT INTO ai_provider_key(organization_id, owner_account_id, capability, provider, base_url, model,
                    encrypted_key, key_version, masked_hint, enabled)
                VALUES (:org, :owner, 'image_generation', 'openai-compatible', 'https://api.example.com', 'org-img',
                    :encrypted, 'v1', 'sk-***org', true)
                """)
                .bind("org", ORG)
                .bind("owner", ORG_ACCOUNT)
                .bind("encrypted", encryption.encrypt("sk-org-run-secret"))
                .then().block();

        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", signWithOrg(ORG_ACCOUNT, ORG))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"x","maxTokens":16,"allowFallback":true}
                        """)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.error").isEqualTo("无 BYOK 且未授权回退平台模型");

        CREDITS.verify(0, postRequestedFor(urlEqualTo("/internal/credits/consume")));

        db.sql("INSERT INTO ai_org_byok_policy(organization_id, allow_platform_fallback, updated_by_account_id) "
                        + "VALUES (:org, true, :owner)")
                .bind("org", ORG)
                .bind("owner", ORG_ACCOUNT)
                .then().block();

        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", signWithOrg(ORG_ACCOUNT, ORG))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"x","maxTokens":16,"allowFallback":true}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.taskContext.resolutionType").isEqualTo("PLATFORM")
                .jsonPath("$.taskContext.fallbackAuthorized").isEqualTo(true);
    }

    @Test
    @DisplayName("无 BYOK 且未授权回退 → 403；不扣分、不落 Run（HLD §12.3 硬规则）")
    void fallbackUnauthorizedDenied() {
        stubQwenOk();
        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"x","maxTokens":16,"allowFallback":false}
                        """)
                .exchange()
                .expectStatus().isForbidden();

        Long runs = db.sql("SELECT COUNT(*) AS n FROM ai_run").map((r, m) -> r.get("n", Long.class)).one().block();
        assertThat(runs).isEqualTo(0);
        CREDITS.verify(0, postRequestedFor(urlEqualTo("/internal/credits/consume")));
    }

    @Test
    @DisplayName("未声明 allowFallback 默认拒绝平台模型，不扣分、不落 Run")
    void omittedFallbackDefaultsToDenied() {
        stubQwenOk();
        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"x","maxTokens":16}
                        """)
                .exchange()
                .expectStatus().isForbidden();

        Long runs = db.sql("SELECT COUNT(*) AS n FROM ai_run").map((r, m) -> r.get("n", Long.class)).one().block();
        assertThat(runs).isEqualTo(0);
        CREDITS.verify(0, postRequestedFor(urlEqualTo("/internal/credits/consume")));
    }

    @Test
    @DisplayName("当前同步入口拒绝非 text capability")
    void nonTextCapabilityRejected() {
        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"image_generation","prompt":"x","maxTokens":16,"allowFallback":true}
                        """)
                .exchange()
                .expectStatus().isBadRequest();

        Long runs = db.sql("SELECT COUNT(*) AS n FROM ai_run")
                .map((r, m) -> r.get("n", Long.class)).one().block();
        assertThat(runs).isZero();
        CREDITS.verify(0, postRequestedFor(urlEqualTo("/internal/credits/consume")));
    }

    @Test
    @DisplayName("provider URL 同步校验失败也会标记 Run 失败并退款")
    void synchronousProviderValidationFailureRefundsAndMarksRunFailed() {
        doThrow(new IllegalArgumentException("synchronous provider validation failure"))
                .when(textClient).complete(
                        anyString(), anyString(), anyString(), anyString(), anyInt(), eq(false));

        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"x","maxTokens":16,"allowFallback":true}
                        """)
                .exchange()
                .expectStatus().isBadRequest();

        Long failed = db.sql("SELECT COUNT(*) AS n FROM ai_run WHERE status = 'failed'")
                .map((r, m) -> r.get("n", Long.class)).one().block();
        assertThat(failed).isEqualTo(1);
        CREDITS.verify(1, postRequestedFor(urlEqualTo("/internal/credits/consume")));
        CREDITS.verify(1, postRequestedFor(urlEqualTo("/internal/credits/consume-compensations")));
        Long events = db.sql("SELECT COUNT(*) AS n FROM intelligence_outbox WHERE event_type = 'AiRunFailed'")
                .map((r, m) -> r.get("n", Long.class)).one().block();
        assertThat(events).isEqualTo(1);
    }

    @Test
    @DisplayName("BYOK 在预算检查中估算为 0 分")
    void byokUsesZeroEstimatedCents() {
        doReturn(Mono.just(new TextCompletionResult("hello", 10, 5)))
                .when(textClient).complete(anyString(), anyString(), anyString(), anyString(), anyInt(), eq(true));
        db.sql("INSERT INTO ai_model_budget(organization_id, capability, provider, max_cents_per_run, enabled) "
                        + "VALUES (:org, 'text', 'platform', 0, true)")
                .bind("org", ORG).then().block();
        db.sql("""
                INSERT INTO ai_provider_key(owner_account_id, capability, provider, base_url, model,
                    encrypted_key, key_version, masked_hint, enabled)
                VALUES (:owner, 'text', 'openai-compatible', 'https://api.example.com', 'unpriced-byok',
                    :encrypted, 'v1', 'sk-***', true)
                """)
                .bind("owner", ORG_ACCOUNT)
                .bind("encrypted", encryption.encrypt("sk-byok-secret"))
                .then().block();

        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", signWithOrg(ORG_ACCOUNT, ORG))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"x","maxTokens":16}
                        """)
                .exchange()
                .expectStatus().isOk();

        Integer reserved = db.sql("SELECT budget_cents FROM ai_run WHERE account_id = :account")
                .bind("account", ORG_ACCOUNT)
                .map((r, m) -> r.get("budget_cents", Integer.class)).one().block();
        assertThat(reserved).isZero();
        CREDITS.verify(0, postRequestedFor(urlEqualTo("/internal/credits/consume")));
    }

    @Test
    @DisplayName("平台执行只使用 platform 预算，不受同组织 BYOK 预算影响")
    void platformRunUsesPlatformBudget() {
        stubQwenOk();
        db.sql("INSERT INTO ai_model_budget(organization_id, capability, provider, max_tokens_per_run, enabled) "
                + "VALUES (:org, 'text', 'platform', 1, true), "
                + "(:org, 'text', 'openai-compatible', 1000, true)")
                .bind("org", ORG).then().block();

        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", signWithOrg(ORG_ACCOUNT, ORG))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"x","maxTokens":16,"allowFallback":true}
                        """)
                .exchange().expectStatus().isEqualTo(402);

        CREDITS.verify(0, postRequestedFor(urlEqualTo("/internal/credits/consume")));
    }

    @Test
    @DisplayName("组织全局月度金额预算优先并在执行前硬停")
    void organizationMonthlyCentsBudgetBlocksRun() {
        stubQwenOk();
        db.sql("INSERT INTO ai_model_budget(organization_id, capability, provider, "
                        + "max_cents_monthly, enabled) VALUES (:org, '*', '*', 0, true)")
                .bind("org", ORG).then().block();

        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", signWithOrg(ORG_ACCOUNT, ORG))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"x","maxTokens":16,"allowFallback":true}
                        """)
                .exchange().expectStatus().isEqualTo(402);

        Long runs = db.sql("SELECT COUNT(*) AS n FROM ai_run")
                .map((row, meta) -> row.get("n", Long.class)).one().block();
        assertThat(runs).isZero();
        CREDITS.verify(0, postRequestedFor(urlEqualTo("/internal/credits/consume")));
        QWEN.verify(0, postRequestedFor(urlEqualTo("/chat/completions")));
    }

    @Test
    @DisplayName("BYOK 执行只使用解析后的 provider 预算")
    void byokRunUsesResolvedProviderBudget() {
        db.sql("INSERT INTO ai_model_budget(organization_id, capability, provider, max_tokens_per_run, enabled) "
                + "VALUES (:org, 'text', 'platform', 1000, true), "
                + "(:org, 'text', 'openai-compatible', 1, true)")
                .bind("org", ORG).then().block();
        db.sql("""
                INSERT INTO ai_provider_key(owner_account_id, capability, provider, base_url, model,
                    encrypted_key, key_version, masked_hint, enabled)
                VALUES (:owner, 'text', 'openai-compatible', 'https://api.example.com', 'byok-model',
                    :encrypted, 'v1', 'sk-***', true)
                """)
                .bind("owner", ORG_ACCOUNT)
                .bind("encrypted", encryption.encrypt("sk-byok-secret"))
                .then().block();

        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", signWithOrg(ORG_ACCOUNT, ORG))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"x","maxTokens":16}
                        """)
                .exchange().expectStatus().isEqualTo(402);

        CREDITS.verify(0, postRequestedFor(urlEqualTo("/internal/credits/consume")));
    }

    @Test
    @DisplayName("无价目表的平台模型 fail-closed 且不扣积分、不落 Run")
    void unknownPlatformModelPriceFailsClosed() {
        db.sql("UPDATE platform_model_config SET model = 'unpriced-platform-model' WHERE enabled = true")
                .then().block();

        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"x","maxTokens":16,"allowFallback":true}
                        """)
                .exchange()
                .expectStatus().isEqualTo(503);

        Long runs = db.sql("SELECT COUNT(*) AS n FROM ai_run")
                .map((r, m) -> r.get("n", Long.class)).one().block();
        assertThat(runs).isZero();
        CREDITS.verify(0, postRequestedFor(urlEqualTo("/internal/credits/consume")));
    }

    @Test
    @DisplayName("组织预算超限 → 402 exceeds_run_budget")
    void budgetExceededDenied() {
        stubQwenOk();
        db.sql("INSERT INTO ai_model_budget(organization_id, capability, provider, max_tokens_per_run, enabled) "
                + "VALUES (:org, 'text', 'platform', 1, true)").bind("org", ORG).then().block();

        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", signWithOrg(ORG_ACCOUNT, ORG))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"x","maxTokens":128,"allowFallback":true}
                        """)
                .exchange()
                .expectStatus().isEqualTo(402);

        CREDITS.verify(0, postRequestedFor(urlEqualTo("/internal/credits/consume")));
    }

    @Test
    @DisplayName("预算原子预留：同一余额只允许一个并发请求通过")
    void budgetReservationIsAtomic() {
        db.sql("INSERT INTO ai_model_budget(organization_id, capability, provider, max_tokens_daily, enabled) "
                + "VALUES (:org, 'text', 'platform', 100, true)").bind("org", ORG).then().block();
        var results = reactor.core.publisher.Flux.merge(
                        budgetService.checkAndReserve(ORG, "text", "platform", 80, 0),
                        budgetService.checkAndReserve(ORG, "text", "platform", 80, 0))
                .collectList().block();

        assertThat(results).hasSize(2);
        assertThat(results.stream().filter(ModelBudgetService.BudgetCheckResult::allowed).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("BYOK 低报 usage 不能释放服务端 token 预留，金额保持为零")
    void byokUsageCountsTowardOrganizationTokenBudget() {
        doReturn(Mono.just(new TextCompletionResult("hello", 10, 5)))
                .when(textClient).complete(anyString(), anyString(), anyString(), anyString(), anyInt(), eq(true));
        db.sql("INSERT INTO ai_model_budget(organization_id, capability, provider, max_tokens_daily, enabled) "
                + "VALUES (:org, 'text', 'openai-compatible', 1000, true)").bind("org", ORG).then().block();
        db.sql("""
                INSERT INTO ai_provider_key(owner_account_id, capability, provider, base_url, model,
                    encrypted_key, key_version, masked_hint, enabled)
                VALUES (:owner, 'text', 'openai-compatible', 'https://api.example.com', 'byok-model',
                    :encrypted, 'v1', 'sk-***', true)
                """)
                .bind("owner", ORG_ACCOUNT)
                .bind("encrypted", encryption.encrypt("sk-byok-secret"))
                .then().block();

        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", signWithOrg(ORG_ACCOUNT, ORG))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"x","maxTokens":32}
                        """)
                .exchange().expectStatus().isOk();

        var usage = db.sql("SELECT current_daily_tokens, current_daily_cents FROM ai_model_budget WHERE organization_id=:org")
                .bind("org", ORG)
                .map((row, meta) -> java.util.List.of(
                        row.get("current_daily_tokens", Long.class), row.get("current_daily_cents", Long.class)))
                .one().block();
        assertThat(usage).containsExactly(33L, 0L);
    }

    @Test
    @DisplayName("预算估算包含 prompt 与最大输出 token")
    void promptTokensCountTowardRunBudget() {
        stubQwenOk();
        db.sql("INSERT INTO ai_model_budget(organization_id, capability, provider, max_tokens_per_run, enabled) "
                + "VALUES (:org, 'text', 'platform', 10, true)").bind("org", ORG).then().block();

        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", signWithOrg(ORG_ACCOUNT, ORG))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"这是一个明显超过十个字符的提示内容","maxTokens":1,"allowFallback":true}
                        """)
                .exchange().expectStatus().isEqualTo(402);

        CREDITS.verify(0, postRequestedFor(urlEqualTo("/internal/credits/consume")));
    }

    @Test
    @DisplayName("emoji 按 UTF-8 字节保守估算输入 token")
    void emojiPromptUsesConservativeTokenEstimate() {
        stubQwenOk();
        db.sql("INSERT INTO ai_model_budget(organization_id, capability, provider, max_tokens_per_run, enabled) "
                + "VALUES (:org, 'text', 'platform', 2, true)").bind("org", ORG).then().block();

        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", signWithOrg(ORG_ACCOUNT, ORG))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"😀","maxTokens":1,"allowFallback":true}
                        """)
                .exchange().expectStatus().isEqualTo(402);

        CREDITS.verify(0, postRequestedFor(urlEqualTo("/internal/credits/consume")));
    }

    @Test
    @DisplayName("预算结算失败时 Run 转 failed 且不发送 completed 事件")
    void settlementFailureDoesNotPublishCompletedEvent() {
        stubQwenOk();
        db.sql("INSERT INTO ai_model_budget(organization_id, capability, provider, max_tokens_daily, enabled) "
                + "VALUES (:org, 'text', 'platform', 1000, true)").bind("org", ORG).then().block();
        doReturn(Mono.just(false)).when(budgetService)
                .settleReservation(any(ModelBudgetService.BudgetCheckResult.class), anyLong(), anyLong());

        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", signWithOrg(ORG_ACCOUNT, ORG))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"x","maxTokens":16,"allowFallback":true}
                        """)
                .exchange().expectStatus().is5xxServerError();

        Long completedEvents = db.sql("SELECT COUNT(*) AS n FROM intelligence_outbox WHERE event_type='AiRunCompleted'")
                .map((row, meta) -> row.get("n", Long.class)).one().block();
        Long failedRuns = db.sql("SELECT COUNT(*) AS n FROM ai_run WHERE status='failed'")
                .map((row, meta) -> row.get("n", Long.class)).one().block();
        assertThat(completedEvents).isZero();
        assertThat(failedRuns).isEqualTo(1);
        CREDITS.verify(1, postRequestedFor(urlEqualTo("/internal/credits/consume-compensations")));
    }

    @Test
    @DisplayName("Run 完成状态写入失败时不发送 completed 事件")
    void completionStateFailureDoesNotPublishCompletedEvent() {
        stubQwenOk();
        doReturn(Mono.just(false)).when(runRepository)
                .complete(any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());

        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"x","maxTokens":16,"allowFallback":true}
                        """)
                .exchange().expectStatus().is5xxServerError();

        Long completedEvents = db.sql("SELECT COUNT(*) AS n FROM intelligence_outbox WHERE event_type='AiRunCompleted'")
                .map((row, meta) -> row.get("n", Long.class)).one().block();
        Long failedRuns = db.sql("SELECT COUNT(*) AS n FROM ai_run WHERE status='failed'")
                .map((row, meta) -> row.get("n", Long.class)).one().block();
        assertThat(completedEvents).isZero();
        assertThat(failedRuns).isEqualTo(1);
        CREDITS.verify(1, postRequestedFor(urlEqualTo("/internal/credits/consume-compensations")));
    }

    @Test
    @DisplayName("跨日释放预留不会扣减新日窗口")
    void reservationReleaseDoesNotPolluteNextDailyWindow() {
        db.sql("INSERT INTO ai_model_budget(organization_id, capability, provider, max_tokens_daily, enabled) "
                + "VALUES (:org, 'text', 'platform', 1000, true)").bind("org", ORG).then().block();
        ModelBudgetService.BudgetCheckResult reservation =
                budgetService.checkAndReserve(ORG, "text", "platform", 20, 4).block();
        db.sql("UPDATE ai_model_budget SET current_daily_tokens=7, current_daily_cents=3, "
                + "last_reset_date=CURRENT_DATE + 1 WHERE organization_id=:org")
                .bind("org", ORG).then().block();

        assertThat(budgetService.releaseReservation(reservation).block()).isTrue();

        var daily = db.sql("SELECT current_daily_tokens, current_daily_cents FROM ai_model_budget WHERE organization_id=:org")
                .bind("org", ORG)
                .map((row, meta) -> java.util.List.of(
                        row.get("current_daily_tokens", Long.class), row.get("current_daily_cents", Long.class)))
                .one().block();
        assertThat(daily).containsExactly(7L, 3L);
    }

    @Test
    @DisplayName("跨月释放预留不会扣减新月窗口")
    void reservationReleaseDoesNotPolluteNextMonthlyWindow() {
        db.sql("INSERT INTO ai_model_budget(organization_id, capability, provider, max_tokens_monthly, enabled) "
                + "VALUES (:org, 'text', 'platform', 1000, true)").bind("org", ORG).then().block();
        ModelBudgetService.BudgetCheckResult reservation =
                budgetService.checkAndReserve(ORG, "text", "platform", 20, 4).block();
        db.sql("UPDATE ai_model_budget SET current_monthly_tokens=11, current_monthly_cents=5, "
                + "last_reset_date=(CURRENT_DATE + INTERVAL '1 month')::date WHERE organization_id=:org")
                .bind("org", ORG).then().block();

        assertThat(budgetService.releaseReservation(reservation).block()).isTrue();

        var monthly = db.sql("SELECT current_monthly_tokens, current_monthly_cents FROM ai_model_budget WHERE organization_id=:org")
                .bind("org", ORG)
                .map((row, meta) -> java.util.List.of(
                        row.get("current_monthly_tokens", Long.class), row.get("current_monthly_cents", Long.class)))
                .one().block();
        assertThat(monthly).containsExactly(11L, 5L);
    }

    @Test
    @DisplayName("Run 落库失败时不会扣减积分并释放预算")
    void runInsertFailureDoesNotConsumeCreditsAndReleasesBudget() {
        db.sql("INSERT INTO ai_model_budget(organization_id, capability, provider, max_tokens_daily, enabled) "
                + "VALUES (:org, 'text', 'platform', 1000, true)").bind("org", ORG).then().block();
        doReturn(Mono.error(new IllegalStateException("insert failed")))
                .when(runRepository).create(any(AiRun.class));

        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", signWithOrg(ORG_ACCOUNT, ORG))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"x","maxTokens":16,"allowFallback":true}
                        """)
                .exchange().expectStatus().is5xxServerError();

        CREDITS.verify(0, postRequestedFor(urlEqualTo("/internal/credits/consume")));
        CREDITS.verify(0, postRequestedFor(urlEqualTo("/internal/credits/refund")));
        Long reserved = db.sql("SELECT current_daily_tokens FROM ai_model_budget WHERE organization_id=:org")
                .bind("org", ORG).map((row, meta) -> row.get("current_daily_tokens", Long.class)).one().block();
        assertThat(reserved).isZero();
    }

    @Test
    @DisplayName("GET /api/ai/runs/{id} 返回 TaskContext；跨账号 → 404")
    void getRunScoped() {
        stubQwenOk();
        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"x","maxTokens":32,"allowFallback":true}
                        """)
                .exchange()
                .expectStatus().isOk();

        String runId = firstRunId();
        client().get().uri("/api/ai/runs/" + runId)
                .header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.taskContext.priceTableVersion").isEqualTo("v1")
                .jsonPath("$.taskContext.fallbackAuthorized").isEqualTo(true)
                .jsonPath("$.taskContext.capability").isEqualTo("text");

        // 跨账号 → 404（资源按账号作用域）
        client().get().uri("/api/ai/runs/" + runId)
                .header("X-Grassland-Identity", sign("66666666-6666-6666-6666-666666666666", "merchant"))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("Run 绑定本人创作快照并在 TaskContext 返回；他人快照 → 403")
    void creationContextSnapshotIsAccountScopedAndReturned() {
        stubQwenOk();
        String ownedSnapshot = seedCreationContext(ACCOUNT);
        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"x","maxTokens":32,"allowFallback":true,
                         "contextSnapshotId":"%s"}
                        """.formatted(ownedSnapshot))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.taskContext.contextSnapshotId").isEqualTo(ownedSnapshot);

        String persisted = db.sql("SELECT context_snapshot_id::text AS id FROM ai_run LIMIT 1")
                .map(row -> row.get("id", String.class)).one().block();
        assertThat(persisted).isEqualTo(ownedSnapshot);

        db.sql("DELETE FROM ai_run").then().block();
        String foreignSnapshot = seedCreationContext("77777777-7777-7777-7777-777777777777");
        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"x","maxTokens":32,"allowFallback":true,
                         "contextSnapshotId":"%s"}
                        """.formatted(foreignSnapshot))
                .exchange().expectStatus().isForbidden();
        Long runCount = db.sql("SELECT COUNT(*) AS n FROM ai_run")
                .map(row -> row.get("n", Long.class)).one().block();
        assertThat(runCount).isZero();
    }

    // ---------- helpers ----------

    private void stubQwenOk() {
        QWEN.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(okJson("""
                        {"choices":[{"message":{"content":"hello"}}],
                         "usage":{"prompt_tokens":10,"completion_tokens":5}}
                        """)));
    }

    private String seedCreationContext(String accountId) {
        return db.sql("""
                        INSERT INTO creation_context_snapshot(
                            account_id, task_id, application_id, task_version, platform_id, content_form_id,
                            task_snapshot, platform_rules_snapshot, material_snapshot, ai_config_snapshot)
                        SELECT :account, :task, :application, 1, 'xiaohongshu', 'graphic',
                            '{}'::jsonb, '{}'::jsonb, '{"items":[]}'::jsonb,
                            jsonb_build_object(
                                'resolutionType', 'PLATFORM', 'configId', id::text,
                                'provider', provider, 'model', model,
                                'platformModelVersion', version, 'modelRole', model_role)
                        FROM platform_model_config
                        WHERE capability='text' AND model_role='primary' AND enabled=true
                        RETURNING id::text
                        """)
                .bind("account", accountId)
                .bind("task", UUID.randomUUID().toString())
                .bind("application", UUID.randomUUID().toString())
                .map(row -> row.get("id", String.class)).one().block();
    }

    private void stubCreditsOk() {
        stubEntitlement(ACCOUNT);
        stubEntitlement(ORG_ACCOUNT);
        CREDITS.stubFor(post(urlEqualTo("/internal/credits/consume")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"success\":true,\"data\":{\"source\":\"quota\","
                        + "\"policyVersion\":1,\"transactionId\":"
                        + "\"11111111-1111-1111-1111-111111111111\"}}")));
        CREDITS.stubFor(post(urlEqualTo("/internal/credits/refund")).willReturn(aResponse().withStatus(200)));
        CREDITS.stubFor(post(urlEqualTo("/internal/credits/consume-compensations"))
                .willReturn(aResponse().withStatus(200)));
    }

    private void stubEntitlement(String accountId) {
        CREDITS.stubFor(get(urlEqualTo(
                        "/internal/marketplace/reputation/" + accountId + "/ai-entitlement"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"data\":{\"accountId\":\"" + accountId
                                + "\",\"aiQuotaMultiplierBps\":10000,\"policyVersion\":1}}")));
    }

    private String firstRunId() {
        return db.sql("SELECT id::text AS id FROM ai_run ORDER BY started_at DESC LIMIT 1")
                .map((r, m) -> r.get("id", String.class)).one().block();
    }

    private void awaitRunStatus(String status) {
        Mono.defer(() -> db.sql("SELECT status FROM ai_run ORDER BY started_at DESC LIMIT 1")
                        .map((row, meta) -> row.get("status", String.class)).one())
                .filter(status::equals)
                .repeatWhenEmpty(repeats -> repeats.delayElements(Duration.ofMillis(25)))
                .block(Duration.ofSeconds(5));
    }

    @TestConfiguration
    static class DnsTestConfiguration {
        @Bean
        @Primary
        DnsPinningResolver deterministicDnsPinningResolver() {
            return DnsPinningResolver.create(host -> {
                try {
                    return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
                } catch (java.net.UnknownHostException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }
}
