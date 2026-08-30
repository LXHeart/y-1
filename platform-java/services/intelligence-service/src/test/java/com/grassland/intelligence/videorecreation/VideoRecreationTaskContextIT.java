package com.grassland.intelligence.videorecreation;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.articleimage.ArticleImageService;
import com.grassland.intelligence.articleimage.FrozenImageGenerationConfigResolver;
import com.grassland.intelligence.articleimage.GeneratedImageResponse;
import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.media.MediaPurpose;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

@DisplayName("Video recreation task context")
class VideoRecreationTaskContextIT extends IntelligenceItSupport {
    private static final String ACCOUNT = "61616161-6161-6161-6161-616161616161";
    private static final String OTHER = "62626262-6262-6262-6262-626262626262";
    // 平台凭据密钥走信封加密（任务书 #58 决策 E：无 env key 兜底，IT 必须配真凭据）
    private static final String TEST_KEK_BASE64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @org.springframework.test.context.DynamicPropertySource
    static void kekProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("crypto.kek.encoded", () -> TEST_KEK_BASE64);
    }

    @MockitoBean
    ArticleImageService images;

    @MockitoBean
    CreditsClient credits;

    @Autowired
    FrozenImageGenerationConfigResolver frozenImages;

    @Autowired
    org.springframework.beans.factory.ObjectProvider<com.grassland.crypto.EnvelopeEncryption> encryptionProvider;

    private final ObjectMapper mapper = new ObjectMapper();
    private String platformConfigId;

    @BeforeEach
    void cleanAndSeed() {
        reset(images, credits);
        when(images.generate(any(), any(), eq(MediaPurpose.VIDEO_ASSET), any())).thenReturn(Mono.just(
                new GeneratedImageResponse("/api/article-generation/generated-images/task-video", "优化后")));
        when(credits.consume(anyString(), any(CreditFeature.class), anyString()))
                .thenAnswer(invocation -> Mono.just(new CreditCharge(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2))));
        when(credits.refund(any(), anyString())).thenReturn(Mono.empty());
        when(credits.compensate(any(), anyString())).thenReturn(Mono.empty());

        db.sql("DELETE FROM intelligence_outbox").then().block();
        db.sql("DELETE FROM ai_credit_compensation").then().block();
        db.sql("DELETE FROM video_generation_job").then().block();
        db.sql("DELETE FROM ai_run").then().block();
        db.sql("DELETE FROM creation_context_snapshot").then().block();
        db.sql("DELETE FROM ai_model_budget").then().block();
        db.sql("DELETE FROM platform_model_concurrency_slot").then().block();
        db.sql("DELETE FROM platform_model_config").then().block();
        db.sql("DELETE FROM platform_provider_credential WHERE base_url LIKE 'http://localhost:%' OR name LIKE 'recreation-%'").then().block();
        // 任务书 #58：text 行也带凭据密钥（改编走冻结平台解析，无凭据即 503 fail-closed）
        String textEncryptedKey = encryptionProvider.getIfAvailable().encrypt("sk-recreation-text-key");
        platformConfigId = db.sql("""
                        WITH cred AS (
                            INSERT INTO platform_provider_credential(name, provider, base_url,
                                encrypted_key, key_version, masked_hint, enabled)
                            VALUES ('recreation-text', 'qwen', :baseUrl, :textKey, 'v1', 'sk-***txt', true)
                            RETURNING id
                        )
                        INSERT INTO platform_model_config(
                            capability, model_role, provider, model, base_url,
                            health_status, enabled, version, credential_id)
                        SELECT 'text','primary','qwen','qwen-plus',:baseUrl,'healthy',true,13,cred.id
                        FROM cred
                        RETURNING id::text
                        """)
                .bind("baseUrl", QWEN.baseUrl())
                .bind("textKey", textEncryptedKey)
                .map(row -> row.get("id", String.class)).one().block();
        // 任务书 #58 决策 G：任务模式平台生图=控制面 image_generation 行+凭据（静态 env 已删）
        seedImageGenerationRow();
        QWEN.resetAll();
    }

    @Test
    @DisplayName("adaptation and scene image reuse one frozen snapshot and create separate audited runs")
    void adaptationAndImageReuseSnapshot() throws Exception {
        String snapshotId = seedSnapshot(ACCOUNT, "xiaohongshu", "video");
        QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(okJson("""
                {"choices":[{"message":{"content":"{\\"adapted_summary\\":\\"冻结改编\\",\\"adapted_script\\":[],\\"adapted_voice_description\\":\\"温和\\",\\"visual_style\\":\\"纪实\\",\\"tone\\":\\"轻松\\",\\"character_sheets\\":[],\\"scene_cards\\":[],\\"prop_cards\\":[]}"}}],
                 "usage":{"prompt_tokens":41,"completion_tokens":17}}
                """)));

        Map<String, Object> adapt = new LinkedHashMap<>();
        adapt.put("platform", "bilibili");
        adapt.put("proxyVideoUrl", "/api/bilibili/proxy/reference-token");
        adapt.put("extractedContent", Map.of("videoScript", "参考视频脚本"));
        addTaskBinding(adapt, snapshotId, "xiaohongshu");
        client().post().uri("/api/video-recreation/adapt-content")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(adapt)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.adaptedSummary").isEqualTo("冻结改编");

        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("scene", Map.of(
                "shotDescription", "招牌特写", "characterDescription", "店员",
                "actionMovement", "递出餐品", "dialogueVoiceover", "欢迎品尝",
                "sceneEnvironment", "夜市摊位"));
        scene.put("overallStyle", "纪实");
        addTaskBinding(scene, snapshotId, "xiaohongshu");
        client().post().uri("/api/video-recreation/generate-scene-image")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(scene)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.imageUrl")
                .isEqualTo("/api/article-generation/generated-images/task-video");

        QWEN.verify(1, postRequestedFor(urlEqualTo("/chat/completions"))
                .withRequestBody(containing("必须展示新品包装"))
                .withRequestBody(containing("contextSnapshotId")));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ArticleImageService.GenerateCommand> command =
                ArgumentCaptor.forClass(ArticleImageService.GenerateCommand.class);
        verify(images).generate(command.capture(), any(), eq(MediaPurpose.VIDEO_ASSET), any());
        assertThat(command.getValue().prompt())
                .contains("必须展示新品包装")
                .contains("material-video-1")
                .contains("招牌特写");

        List<Map<String, Object>> audits = db.sql("""
                        SELECT capability, context_snapshot_id::text AS snapshot_id, status, images_generated
                        FROM ai_run ORDER BY started_at
                        """)
                .<Map<String, Object>>map((Row row, RowMetadata metadata) -> {
                    Map<String, Object> audit = new LinkedHashMap<>();
                    audit.put("capability", row.get("capability", String.class));
                    audit.put("snapshotId", row.get("snapshot_id", String.class));
                    audit.put("status", row.get("status", String.class));
                    audit.put("images", row.get("images_generated", Integer.class));
                    return audit;
                })
                .all().collectList().block();
        assertThat(audits).hasSize(2);
        assertThat(audits).allSatisfy(audit -> {
            assertThat(audit).containsEntry("snapshotId", snapshotId)
                    .containsEntry("status", "completed");
        });
        assertThat(audits).extracting(audit -> audit.get("capability"))
                .containsExactly("text", "image_generation");
    }

    @Test
    @DisplayName("task recreation rejects missing, foreign, mismatched, non-video, and smuggled snapshots")
    void taskModeFailsClosed() throws Exception {
        postScene(ACCOUNT, null, "xiaohongshu").expectStatus().isBadRequest();

        String foreign = seedSnapshot(OTHER, "xiaohongshu", "video");
        postScene(ACCOUNT, foreign, "xiaohongshu").expectStatus().isForbidden();

        String mismatched = seedSnapshot(ACCOUNT, "douyin", "video");
        postScene(ACCOUNT, mismatched, "xiaohongshu").expectStatus().isEqualTo(409);

        String graphic = seedSnapshot(ACCOUNT, "xiaohongshu", "graphic");
        postScene(ACCOUNT, graphic, "xiaohongshu").expectStatus().isEqualTo(409);

        Map<String, Object> independent = sceneBody();
        independent.put("contextSnapshotId", UUID.randomUUID().toString());
        client().post().uri("/api/video-recreation/generate-scene-image")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(independent)
                .exchange().expectStatus().isBadRequest();
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec postScene(
            String account, String snapshotId, String platform) {
        Map<String, Object> body = sceneBody();
        body.put("taskMode", true);
        body.put("targetPlatform", platform);
        if (snapshotId != null) body.put("contextSnapshotId", snapshotId);
        return client().post().uri("/api/video-recreation/generate-scene-image")
                .header("X-Grassland-Identity", sign(account, "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private static Map<String, Object> sceneBody() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scene", Map.of(
                "shotDescription", "镜头", "characterDescription", "角色",
                "actionMovement", "动作", "dialogueVoiceover", "旁白",
                "sceneEnvironment", "环境"));
        return result;
    }

    private static void addTaskBinding(Map<String, Object> body, String snapshotId, String platform) {
        body.put("taskMode", true);
        body.put("contextSnapshotId", snapshotId);
        body.put("targetPlatform", platform);
    }

    private void seedImageGenerationRow() {
        String encryptedKey = encryptionProvider.getIfAvailable().encrypt("sk-task-image-generation");
        db.sql("""
                        WITH cred AS (
                            INSERT INTO platform_provider_credential(name, provider, base_url,
                                encrypted_key, key_version, masked_hint, enabled)
                            VALUES ('recreation-image', 'qwen', 'https://recreation-image.example/v1',
                                :encryptedKey, 'v1', 'sk-***task', true)
                            RETURNING id
                        )
                        INSERT INTO platform_model_config(capability, model_role, provider, model,
                            base_url, max_concurrency, health_status, enabled, version, credential_id)
                        SELECT 'image_generation','primary','qwen','wanx-v1','https://recreation-image.example/v1',
                            1,'healthy',true,1,cred.id
                        FROM cred
                        """)
                .bind("encryptedKey", encryptedKey)
                .then().block();
    }

    private String seedSnapshot(String accountId, String platform, String contentForm) throws Exception {
        Map<String, Object> aiConfig = new LinkedHashMap<>();
        aiConfig.put("resolutionType", "PLATFORM");
        aiConfig.put("configId", platformConfigId);
        aiConfig.put("provider", "qwen");
        aiConfig.put("model", "qwen-plus");
        aiConfig.put("platformModelVersion", 13);
        aiConfig.put("modelRole", "primary");
        aiConfig.put("imageGeneration", frozenImages.platformSnapshot().block());
        return db.sql("""
                        INSERT INTO creation_context_snapshot(
                            account_id, organization_id, task_id, application_id, task_version,
                            platform_id, content_form_id, task_snapshot, platform_rules_snapshot,
                            material_snapshot, ai_config_snapshot)
                        VALUES (:account,'org-video',:task,:application,6,:platform,:contentForm,
                            '{"title":"视频复刻任务","requirements":{"mustInclude":["必须展示新品包装"]}}'::jsonb,
                            '{"version":"2026-08-06","maxSeconds":60}'::jsonb,
                            '{"items":[{"assetId":"material-video-1","version":2}]}'::jsonb,
                            CAST(:aiConfig AS jsonb))
                        RETURNING id::text
                        """)
                .bind("account", accountId)
                .bind("task", UUID.randomUUID().toString())
                .bind("application", UUID.randomUUID().toString())
                .bind("platform", platform)
                .bind("contentForm", contentForm)
                .bind("aiConfig", mapper.writeValueAsString(aiConfig))
                .map(row -> row.get("id", String.class)).one().block();
    }
}
