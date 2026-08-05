package com.grassland.intelligence.ai.run;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 控制面 Run 执行闭环（GL-P3-AI-001）：平台 run（扣分/结算/事件）、平台 run 失败退款、
 * BYOK run（解密→调用链，不扣分）、BYOK 回退未授权拒绝、预算超限拒绝、TaskContext 快照。
 *
 * <p>CREDITS WireMock 托管 legacy 积分端点；QWEN WireMock 托管 provider（平台 + BYOK 共用）。
 * KEK 注入使 BYOK 密钥创建 + 运行时解密可用（与 {@code AiProviderKeyControllerIT} 同值）。
 */
@DisplayName("AiRunController (控制面执行闭环)")
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

    @DynamicPropertySource
    static void extraProps(DynamicPropertyRegistry r) {
        r.add("crypto.kek.encoded", () -> TEST_KEK_BASE64);
        r.add("credits.legacy.base-url", CREDITS::baseUrl);
        r.add("credits.legacy.internal-key", () -> "test-internal-key");
        // 默认 CreditsClient 已是 FinanceCreditsClient（GL-P3-AI-001）；把它指向同一个 WireMock，
        // 使 consume/refund 走生产路径打到桩端点。credits.legacy.* 退化为未使用配置。
        r.add("credits.finance.base-url", CREDITS::baseUrl);
        r.add("credits.finance.internal-key", () -> "test-internal-key");
    }

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM intelligence_outbox").then().block();
        db.sql("DELETE FROM ai_run").then().block();
        db.sql("DELETE FROM ai_provider_key").then().block();
        db.sql("DELETE FROM ai_model_budget").then().block();
        db.sql("DELETE FROM platform_model_config").then().block();
        // 自种子平台 text/primary 配置指向 QWEN（不依赖启动期 seeder，保证跨测试类隔离）
        db.sql("INSERT INTO platform_model_config(capability, model_role, provider, model, base_url, "
                + "health_status, enabled, version) VALUES ('text','primary','qwen','qwen-plus',:baseUrl,'healthy',true,1)")
                .bind("baseUrl", QWEN.baseUrl()).then().block();
        CREDITS.resetAll();
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
                        {"capability":"text","prompt":"写一句问候","maxTokens":128}
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
        // 平台扣分一次
        CREDITS.verify(1, postRequestedFor(urlEqualTo("/internal/credits/consume")));
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
                        {"capability":"text","prompt":"x","maxTokens":64}
                        """)
                .exchange()
                .expectStatus().is5xxServerError();

        Long failed = db.sql("SELECT COUNT(*) AS n FROM ai_run WHERE status='failed'")
                .map((r, m) -> r.get("n", Long.class)).one().block();
        assertThat(failed).isEqualTo(1);
        CREDITS.verify(1, postRequestedFor(urlEqualTo("/internal/credits/consume")));
        CREDITS.verify(1, postRequestedFor(urlEqualTo("/internal/credits/refund")));  // 失败退款（GL-P0-BILL-002）
        Long events = db.sql("SELECT COUNT(*) AS n FROM intelligence_outbox WHERE event_type='AiRunFailed'")
                .map((r, m) -> r.get("n", Long.class)).one().block();
        assertThat(events).isEqualTo(1);
    }

    @Test
    @DisplayName("BYOK run：解密密钥→调用 provider；actualCents=0；不扣平台积分")
    void byokRunDecryptsAndCalls() {
        stubQwenOk();
        // 注册个人 BYOK 密钥（baseUrl=QWEN，provider 调用时用解密后的 bearer）
        client().post().uri("/api/ai/keys")
                .header("X-Grassland-Identity", sign(BYOK_ACCOUNT, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","provider":"openai-compatible","baseUrl":"%s","model":"byok-model","apiKey":"sk-test-byok-secret"}
                        """.formatted(QWEN.baseUrl()))
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
    @DisplayName("组织预算超限 → 402 exceeds_run_budget")
    void budgetExceededDenied() {
        stubQwenOk();
        db.sql("INSERT INTO ai_model_budget(organization_id, capability, provider, max_tokens_per_run, enabled) "
                + "VALUES (:org, 'text', 'platform', 1, true)").bind("org", ORG).then().block();

        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", signWithOrg(ORG_ACCOUNT, ORG))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"x","maxTokens":128}
                        """)
                .exchange()
                .expectStatus().isEqualTo(402);

        CREDITS.verify(0, postRequestedFor(urlEqualTo("/internal/credits/consume")));
    }

    @Test
    @DisplayName("GET /api/ai/runs/{id} 返回 TaskContext；跨账号 → 404")
    void getRunScoped() {
        stubQwenOk();
        client().post().uri("/api/ai/runs")
                .header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","prompt":"x","maxTokens":32}
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

    // ---------- helpers ----------

    private void stubQwenOk() {
        QWEN.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(okJson("""
                        {"choices":[{"message":{"content":"hello"}}],
                         "usage":{"prompt_tokens":10,"completion_tokens":5}}
                        """)));
    }

    private void stubCreditsOk() {
        CREDITS.stubFor(post(urlEqualTo("/internal/credits/consume")).willReturn(aResponse().withStatus(200)));
        CREDITS.stubFor(post(urlEqualTo("/internal/credits/refund")).willReturn(aResponse().withStatus(200)));
    }

    private String firstRunId() {
        return db.sql("SELECT id::text AS id FROM ai_run ORDER BY started_at DESC LIMIT 1")
                .map((r, m) -> r.get("id", String.class)).one().block();
    }
}
