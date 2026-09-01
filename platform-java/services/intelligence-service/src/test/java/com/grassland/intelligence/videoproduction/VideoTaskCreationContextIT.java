package com.grassland.intelligence.videoproduction;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.storage.ObjectStorageAdapter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

@DisplayName("Video task-mode creation context")
@TestPropertySource(properties = "ai.video-generation.worker-enabled=false")
class VideoTaskCreationContextIT extends IntelligenceItSupport {
    private static final String ACCOUNT = "31313131-3131-3131-3131-313131313131";
    private static final String OTHER = "32323232-3232-3232-3232-323232323232";

    @MockitoBean
    CreditsClient credits;

    @MockitoBean
    ObjectStorageAdapter storage;

    @Autowired
    FrozenVideoGenerationConfigResolver frozenVideoConfigs;

    @Autowired
    VideoGenerationProperties videoProperties;

    @Autowired
    VideoGenerationJobRepository jobs;

    @Autowired
    VideoGenerationWorker worker;

    private final ObjectMapper mapper = new ObjectMapper();
    private String platformConfigId;

    @BeforeEach
    void cleanAndSeed() {
        reset(credits, storage);
        when(credits.consume(anyString(), any(CreditFeature.class), anyString()))
                .thenAnswer(invocation -> Mono.just(new CreditCharge(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2))));
        when(credits.refund(any(), anyString())).thenReturn(Mono.empty());
        when(credits.compensate(any(), anyString())).thenReturn(Mono.empty());

        db.sql("DELETE FROM video_generation_job").then().block();
        db.sql("DELETE FROM media_reference WHERE purpose='video_asset'").then().block();
        db.sql("DELETE FROM intelligence_outbox").then().block();
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
                        VALUES ('text','primary','qwen','qwen-plus',:baseUrl,'healthy',true,7)
                        RETURNING id::text
                        """)
                .bind("baseUrl", QWEN.baseUrl())
                .map(row -> row.get("id", String.class)).one().block();
        QWEN.resetAll();
        // 任务书 #58：平台 text 行须挂带密凭据（seeder/env 兜底已删），否则执行层 503
        attachPlatformTextCredential();
    }

    @Test
    @DisplayName("task script injects frozen rules and links the completed text run")
    void taskScriptUsesFrozenContext() {
        String snapshotId = seedSnapshot(ACCOUNT, "douyin", false);
        QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(okJson("""
                {"choices":[{"message":{"content":"【镜头1】冻结任务脚本"}}],
                 "usage":{"prompt_tokens":25,"completion_tokens":8}}
                """)));

        client().post().uri("/api/video-production/generate-script")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(scriptBody(snapshotId, "douyin"))
                .exchange().expectStatus().isOk().expectBody(String.class)
                .value(body -> assertThat(body).contains("冻结任务脚本"));

        QWEN.verify(1, postRequestedFor(urlEqualTo("/chat/completions"))
                .withRequestBody(containing("必须展示门店招牌"))
                .withRequestBody(containing("contextSnapshotId")));
        String audit = db.sql("SELECT context_snapshot_id::text || ':' || status AS audit "
                        + "FROM ai_run ORDER BY started_at DESC LIMIT 1")
                .map(row -> row.get("audit", String.class)).one().block();
        assertThat(audit).isEqualTo(snapshotId + ":completed");
    }

    @Test
    @DisplayName("task endpoints reject missing, foreign, platform-mismatched, and drifted snapshots")
    void taskModeFailsClosed() {
        client().post().uri("/api/video-production/generate-script")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(scriptBody(null, "douyin"))
                .exchange().expectStatus().isBadRequest();

        String foreign = seedSnapshot(OTHER, "douyin", false);
        postScript(ACCOUNT, foreign, "douyin").expectStatus().isForbidden();

        String mismatched = seedSnapshot(ACCOUNT, "kuaishou", false);
        postScript(ACCOUNT, mismatched, "douyin").expectStatus().isEqualTo(409);

        // 任务书 #64 卡6：旧视频通道已 410（在快照校验之前统一拒绝）
        String drifted = seedSnapshot(ACCOUNT, "douyin", true);
        postVideo(ACCOUNT, drifted, "douyin").expectStatus().isEqualTo(410);

        Long jobs = db.sql("SELECT COUNT(*) AS n FROM video_generation_job")
                .map(row -> row.get("n", Long.class)).one().block();
        assertThat(jobs).isZero();
    }

    @Test
    @DisplayName("任务书 #64 卡6：旧 generate-video 停写（410），jobs 端点对历史行只读可用")
    void retiredCreateVideoReturns410AndLegacyJobsStayReadable() {
        // 历史行直插（旧链已无写入口）
        UUID jobId = UUID.randomUUID();
        db.sql("""
                        INSERT INTO video_generation_job(id, account_id, idempotency_key, provider, model,
                            status, progress, input_payload, result_url, requested_duration_seconds,
                            aspect_ratio, pricing_version, unit_price_cents, estimated_cost_cents,
                            platform_model_version)
                        VALUES (CAST(:id AS uuid), :account, :key, 'sandbox', 'sandbox-video-v1',
                            'succeeded', 100, '{}'::jsonb, NULL, 5, '9:16', 'video-config-v1', 1, 5, 1)
                        """)
                .bind("id", jobId.toString())
                .bind("account", ACCOUNT)
                .bind("key", "legacy-" + jobId)
                .then().block(java.time.Duration.ofSeconds(5));

        client().post().uri("/api/video-production/generate-video")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(videoBody(null, "douyin"))
                .exchange().expectStatus().isEqualTo(410)
                .expectBody().jsonPath("$.error").isEqualTo("旧视频生成通道已下线，请使用分镜成片流程");

        // 只读：owner 仍能读历史行；跨账号 404
        client().get().uri("/api/video-production/jobs/{id}", jobId)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("succeeded");
        client().get().uri("/api/video-production/jobs/{id}", jobId)
                .header("X-Grassland-Identity", sign(OTHER, "recommender"))
                .exchange().expectStatus().isNotFound();
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec postScript(
            String account, String snapshotId, String platform) {
        return client().post().uri("/api/video-production/generate-script")
                .header("X-Grassland-Identity", sign(account, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(scriptBody(snapshotId, platform)).exchange();
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec postVideo(
            String account, String snapshotId, String platform) {
        return client().post().uri("/api/video-production/generate-video")
                .header("X-Grassland-Identity", sign(account, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(videoBody(snapshotId, platform)).exchange();
    }

    private static Map<String, Object> scriptBody(String snapshotId, String platform) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("images", java.util.List.of("AAAA"));
        body.put("shopName", "任务门店");
        body.put("industryType", "餐饮");
        body.put("videoStyle", "烟火纪实");
        body.put("targetPlatform", platform);
        body.put("taskMode", true);
        if (snapshotId != null) body.put("contextSnapshotId", snapshotId);
        return body;
    }

    private static Map<String, Object> videoBody(String snapshotId, String platform) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operationId", UUID.randomUUID().toString());
        body.put("script", "任务脚本");
        body.put("images", java.util.List.of("data:image/png;base64,AAAA"));
        body.put("videoStyle", "烟火纪实");
        body.put("shopName", "任务门店");
        body.put("targetPlatform", platform);
        body.put("taskMode", true);
        if (snapshotId != null) body.put("contextSnapshotId", snapshotId);
        return body;
    }

    private String seedSnapshot(String accountId, String platform, boolean driftVideoConfig) {
        Map<String, Object> aiConfig = new LinkedHashMap<>();
        aiConfig.put("resolutionType", "PLATFORM");
        aiConfig.put("configId", platformConfigId);
        aiConfig.put("provider", "qwen");
        aiConfig.put("model", "qwen-plus");
        aiConfig.put("platformModelVersion", 7);
        aiConfig.put("modelRole", "primary");
        Map<String, Object> video = new LinkedHashMap<>(frozenVideoConfigs.snapshot());
        if (driftVideoConfig) video.put("model", "frozen-but-no-longer-active");
        aiConfig.put("videoGeneration", video);
        try {
            return db.sql("""
                            INSERT INTO creation_context_snapshot(
                                account_id, task_id, application_id, task_version, platform_id, content_form_id,
                                task_snapshot, platform_rules_snapshot, material_snapshot, ai_config_snapshot)
                            VALUES (:account,:task,:application,3,:platform,'video',
                                '{"title":"视频任务","requirements":"必须展示门店招牌"}'::jsonb,
                                '{"version":"2026-08-06","maxSeconds":15}'::jsonb,
                                '{"items":[]}'::jsonb,CAST(:aiConfig AS jsonb))
                            RETURNING id::text
                            """)
                    .bind("account", accountId)
                    .bind("task", UUID.randomUUID().toString())
                    .bind("application", UUID.randomUUID().toString())
                    .bind("platform", platform)
                    .bind("aiConfig", mapper.writeValueAsString(aiConfig))
                    .map(row -> row.get("id", String.class)).one().block();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
