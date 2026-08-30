package com.grassland.intelligence.comedy;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

@DisplayName("Comedy task-mode creation context")
class ComedyTaskCreationContextIT extends IntelligenceItSupport {
    private static final String ACCOUNT = "41414141-4141-4141-4141-414141414141";
    private static final String OTHER = "42424242-4242-4242-4242-424242424242";

    @MockitoBean
    CreditsClient credits;

    private final ObjectMapper mapper = new ObjectMapper();
    private String platformConfigId;

    @BeforeEach
    void cleanAndSeed() {
        reset(credits);
        when(credits.consume(anyString(), any(CreditFeature.class), anyString()))
                .thenAnswer(invocation -> Mono.just(new CreditCharge(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2))));
        when(credits.refund(any(), anyString())).thenReturn(Mono.empty());
        when(credits.compensate(any(), anyString())).thenReturn(Mono.empty());

        db.sql("DELETE FROM ai_credit_compensation").then().block();
        db.sql("DELETE FROM ai_run").then().block();
        db.sql("DELETE FROM creation_context_snapshot").then().block();
        db.sql("DELETE FROM ai_model_budget").then().block();
        db.sql("DELETE FROM platform_model_concurrency_slot").then().block();
        db.sql("DELETE FROM platform_model_config").then().block();
        platformConfigId = db.sql("""
                        INSERT INTO platform_model_config(
                            capability, model_role, provider, model, base_url,
                            health_status, enabled, version)
                        VALUES ('text','primary','qwen','qwen-plus',:baseUrl,'healthy',true,11)
                        RETURNING id::text
                        """)
                .bind("baseUrl", QWEN.baseUrl())
                .map(row -> row.get("id", String.class)).one().block();
        QWEN.resetAll();
        // 任务书 #58：平台 text 行须挂带密凭据（seeder/env 兜底已删），否则执行层 503
        attachPlatformTextCredential();
    }

    @Test
    @DisplayName("task run consumes frozen requirements and persists the snapshot audit link")
    void taskRunUsesFrozenContext() {
        String snapshotId = seedSnapshot(ACCOUNT, "douyin", "video");
        QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(okJson("""
                {"choices":[{"message":{"content":"【铺垫】冻结喜剧脚本"}}],
                 "usage":{"prompt_tokens":31,"completion_tokens":9}}
                """)));

        postTask(ACCOUNT, snapshotId, "douyin")
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("冻结喜剧脚本"));

        QWEN.verify(1, postRequestedFor(urlEqualTo("/chat/completions"))
                .withRequestBody(containing("必须提到夜市招牌"))
                .withRequestBody(containing("contextSnapshotId"))
                .withRequestBody(containing("中文舞台喜剧")));
        String audit = db.sql("SELECT context_snapshot_id::text || ':' || status AS audit "
                        + "FROM ai_run ORDER BY started_at DESC LIMIT 1")
                .map(row -> row.get("audit", String.class)).one().block();
        assertThat(audit).isEqualTo(snapshotId + ":completed");
    }

    @Test
    @DisplayName("task mode rejects missing, foreign, platform-mismatched, and non-video snapshots")
    void taskModeFailsClosed() {
        postTask(ACCOUNT, null, "douyin").expectStatus().isBadRequest();

        String foreign = seedSnapshot(OTHER, "douyin", "video");
        postTask(ACCOUNT, foreign, "douyin").expectStatus().isForbidden();

        String mismatched = seedSnapshot(ACCOUNT, "kuaishou", "video");
        postTask(ACCOUNT, mismatched, "douyin").expectStatus().isEqualTo(409);

        String graphic = seedSnapshot(ACCOUNT, "douyin", "graphic");
        postTask(ACCOUNT, graphic, "douyin").expectStatus().isEqualTo(409);

        Long runs = db.sql("SELECT COUNT(*) AS n FROM ai_run")
                .map(row -> row.get("n", Long.class)).one().block();
        assertThat(runs).isZero();
    }

    @Test
    @DisplayName("independent mode rejects a smuggled task snapshot")
    void independentModeCannotSmuggleSnapshot() {
        String snapshotId = seedSnapshot(ACCOUNT, "douyin", "video");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic", "夜市");
        body.put("duration", 60);
        body.put("contextSnapshotId", snapshotId);

        client().post().uri("/api/comedy-generation/generate-script")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).exchange().expectStatus().isBadRequest();
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec postTask(
            String account, String snapshotId, String platform) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic", "夜市生活");
        body.put("duration", 60);
        body.put("targetPlatform", platform);
        body.put("taskMode", true);
        if (snapshotId != null) body.put("contextSnapshotId", snapshotId);
        return client().post().uri("/api/comedy-generation/generate-script")
                .header("X-Grassland-Identity", sign(account, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).exchange();
    }

    private String seedSnapshot(String accountId, String platform, String contentForm) {
        Map<String, Object> aiConfig = new LinkedHashMap<>();
        aiConfig.put("resolutionType", "PLATFORM");
        aiConfig.put("configId", platformConfigId);
        aiConfig.put("provider", "qwen");
        aiConfig.put("model", "qwen-plus");
        aiConfig.put("platformModelVersion", 11);
        aiConfig.put("modelRole", "primary");
        try {
            return db.sql("""
                            INSERT INTO creation_context_snapshot(
                                account_id, task_id, application_id, task_version, platform_id, content_form_id,
                                task_snapshot, platform_rules_snapshot, material_snapshot, ai_config_snapshot)
                            VALUES (:account,:task,:application,4,:platform,:contentForm,
                                '{"title":"喜剧任务","requirements":"必须提到夜市招牌"}'::jsonb,
                                '{"version":"2026-08-06","maxSeconds":60}'::jsonb,
                                '{"items":[]}'::jsonb,CAST(:aiConfig AS jsonb))
                            RETURNING id::text
                            """)
                    .bind("account", accountId)
                    .bind("task", UUID.randomUUID().toString())
                    .bind("application", UUID.randomUUID().toString())
                    .bind("platform", platform)
                    .bind("contentForm", contentForm)
                    .bind("aiConfig", mapper.writeValueAsString(aiConfig))
                    .map(row -> row.get("id", String.class)).one().block();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
