package com.grassland.intelligence.videoproduction;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 任务书 #65 卡2：AI 补图首帧——未配置 503、用户锚定 409、生成/替换/软删、免费执行环积分零流水、
 * take 生成首帧改用锚定图字节（provider 请求体断言）。
 */
@DisplayName("Shot anchor image generation")
@TestPropertySource(properties = { "ai.video-generation.worker-enabled=false" })
class ShotAnchorImageIT extends IntelligenceItSupport {

    private static final String ACCOUNT = "92929292-9292-9292-9292-929292929292";
    private static final String PAYLOAD_IMAGE = "data:image/png;base64,QUFB";
    /** PNG 签名 8 字节的 base64（与 PAYLOAD_IMAGE 的 QUFB 无子串重叠，请求体断言可区分）。 */
    private static final String ANCHOR_B64 = "iVBORw0KGgo=";
    private static final String ANCHOR_B64_SECOND = "iVBORw0KGgv=";

    @MockitoBean
    CreditsClient credits;

    @MockitoBean
    ObjectStorageAdapter storage;

    @Autowired
    TakeGenerationWorker takeWorker;

    @Autowired
    VideoShotTakeRepository takes;

    @Autowired
    VideoProductionTaskService taskService;

    private final Map<String, byte[]> objectStore = new ConcurrentHashMap<>();

    @BeforeEach
    void cleanAndSeed() {
        reset(credits, storage);
        when(credits.consume(anyString(), any(CreditFeature.class), anyString()))
                .thenAnswer(invocation -> Mono.just(new CreditCharge(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2))));
        when(credits.refund(any(), anyString())).thenReturn(Mono.empty());
        objectStore.clear();
        Mockito.doAnswer(invocation -> {
            objectStore.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(storage).putObject(anyString(), any(byte[].class), anyString());
        when(storage.getObject(anyString()))
                .thenAnswer(invocation -> objectStore.get(invocation.getArgument(0)));
        when(storage.presignDownload(anyString(), any(Long.class)))
                .thenAnswer(invocation -> java.net.URI.create("https://media.example.test/signed"));

        db.sql("DELETE FROM video_shot_take").then()
                .then(db.sql("DELETE FROM video_shot_audio").then())
                .then(db.sql("DELETE FROM video_production_task").then())
                .then(db.sql("DELETE FROM video_shot").then())
                .then(db.sql("DELETE FROM video_storyboard").then())
                .then(db.sql("DELETE FROM media_reference WHERE purpose='anchor_image'").then())
                .then(db.sql("DELETE FROM ai_run").then())
                .then(db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN "
                        + "(SELECT id FROM platform_model_config WHERE capability IN "
                        + "('image_generation','video_generation'))").then())
                .then(db.sql("DELETE FROM platform_model_config WHERE capability IN "
                        + "('image_generation','video_generation')").then())
                .then(db.sql("DELETE FROM platform_provider_credential WHERE base_url=:baseUrl")
                        .bind("baseUrl", QWEN.baseUrl()).then())
                .block(Duration.ofSeconds(10));
        QWEN.resetAll();
    }

    @Test
    @DisplayName("image_generation 未配置 → 503 no_platform_model")
    void unconfiguredImageGenerationReturns503() {
        UUID shotId = seedStoryboardAndShot(0);

        client().post().uri("/api/video-production/shots/{id}/anchor:generate", shotId)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").isEqualTo("平台未配置图片生成模型，请到治理台配置");
    }

    @Test
    @DisplayName("镜头仍绑定用户锚定图 → 409")
    void userAnchoredShotRejected() {
        seedImageModel();
        UUID shotId = seedStoryboardAndShot(1);

        client().post().uri("/api/video-production/shots/{id}/anchor:generate", shotId)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    @DisplayName("生成成功：media 行 anchor_image/active + shot 落锚；重入替换旧图软删；免费环积分零流水")
    void generatePersistsAndReplacesWithZeroCreditFlow() {
        seedImageModel();
        stubImageGeneration(ANCHOR_B64);
        UUID shotId = seedStoryboardAndShot(0);

        client().post().uri("/api/video-production/shots/{id}/anchor:generate", shotId)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.shot.anchorSource").isEqualTo("ai")
                .jsonPath("$.data.shot.anchorUrl").isEqualTo("https://media.example.test/signed");
        String firstMediaId = extractMediaId(shotId);
        assertThat(firstMediaId).isNotBlank();

        // shot 落锚 + media 行 active
        String shotRow = db.sql("SELECT anchor_media_id::text || ':' || anchor_source AS shot_cols FROM video_shot "
                        + "WHERE id=CAST(:id AS uuid)")
                .bind("id", shotId.toString()).map(row -> row.get("shot_cols", String.class)).one().block();
        assertThat(shotRow).isEqualTo(firstMediaId + ":ai");
        String mediaStatus = db.sql("SELECT status FROM media_reference WHERE id=CAST(:id AS uuid)")
                .bind("id", firstMediaId).map(row -> row.get("status", String.class)).one().block();
        assertThat(mediaStatus).isEqualTo("active");
        String purpose = db.sql("SELECT purpose FROM media_reference WHERE id=CAST(:id AS uuid)")
                .bind("id", firstMediaId).map(row -> row.get("purpose", String.class)).one().block();
        assertThat(purpose).isEqualTo("anchor_image");
        assertThat(objectStore.get("media/video_shot_anchor/" + firstMediaId)).isNotNull();

        // 免费执行环：ai_run 留痕（capability=image_generation 且结算 completed）+ credits 零流水（feature=null）
        String run = db.sql("SELECT capability || ':' || status AS cap_row FROM ai_run "
                        + "WHERE capability='image_generation' ORDER BY created_at DESC LIMIT 1")
                .map(row -> row.get("cap_row", String.class)).one().block(Duration.ofSeconds(5));
        assertThat(run).isEqualTo("image_generation:completed");
        verify(credits, Mockito.never()).consume(anyString(), any(CreditFeature.class), anyString());

        // 重入：新图替换，旧 media 软删
        stubImageGeneration(ANCHOR_B64_SECOND);
        client().post().uri("/api/video-production/shots/{id}/anchor:generate", shotId)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isOk();
        String secondMediaId = db.sql("SELECT anchor_media_id::text AS m FROM video_shot "
                        + "WHERE id=CAST(:id AS uuid)")
                .bind("id", shotId.toString()).map(row -> row.get("m", String.class)).one().block();
        assertThat(secondMediaId).isNotEqualTo(firstMediaId);
        String oldStatus = db.sql("SELECT status FROM media_reference WHERE id=CAST(:id AS uuid)")
                .bind("id", firstMediaId).map(row -> row.get("status", String.class)).one().block();
        assertThat(oldStatus).isEqualTo("deleted");
    }

    @Test
    @DisplayName("锚定后 take 生成：provider 请求体首帧用锚定图字节（非 payload 用户图）")
    void anchoredShotUsesAnchorBytesForFirstFrame() {
        seedImageModel();
        stubImageGeneration(ANCHOR_B64);
        UUID shotId = seedStoryboardAndShot(0);
        client().post().uri("/api/video-production/shots/{id}/anchor:generate", shotId)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isOk();

        // 换视频渠道（seedance WireMock）建任务驱动 take
        seedVideoModel("seedance", "qwen-plus");
        QWEN.stubFor(post(urlEqualTo("/api/v3/contents/generations/tasks"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"vid-anchor\"}")));
        QWEN.stubFor(get(urlEqualTo("/api/v3/contents/generations/tasks/vid-anchor"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"succeeded\",\"content\":{\"video_url\":\""
                                + QWEN.baseUrl() + "/files/anchor.mp4\"}}")));
        QWEN.stubFor(get(urlEqualTo("/files/anchor.mp4"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "video/mp4")
                        .withBody(new byte[] { 0, 0, 0, 8, 'f', 't', 'y', 'p', 1, 2 })));

        UUID storyboardId = storyboardIdOf(shotId);
        taskService.create(ACCOUNT, null,
                        new VideoProductionTaskService.CreateRequest(storyboardId, "op-anchor"))
                .block(Duration.ofSeconds(20));
        driveAllTakes(storyboardId);

        QWEN.verify(postRequestedFor(urlEqualTo("/api/v3/contents/generations/tasks"))
                .withRequestBody(containing(ANCHOR_B64)));
        QWEN.verify(0, postRequestedFor(urlEqualTo("/api/v3/contents/generations/tasks"))
                .withRequestBody(containing("QUFB")));
        List<VideoShotTake> all = takes.findByStoryboard(storyboardId).collectList().block();
        assertThat(all).allSatisfy(take -> assertThat(take.status()).isEqualTo(VideoShotTake.STATUS_SUCCEEDED));
    }

    // ---------------- helpers ----------------

    private String extractMediaId(UUID shotId) {
        return db.sql("SELECT anchor_media_id::text AS m FROM video_shot WHERE id=CAST(:id AS uuid)")
                .bind("id", shotId.toString()).map(row -> row.get("m", String.class)).one().block();
    }

    private UUID storyboardIdOf(UUID shotId) {
        return UUID.fromString(db.sql("SELECT storyboard_id::text AS s FROM video_shot WHERE id=CAST(:id AS uuid)")
                .bind("id", shotId.toString()).map(row -> row.get("s", String.class)).one().block());
    }

    private void driveAllTakes(UUID storyboardId) {
        for (int round = 0; round < 4; round++) {
            takes.claimBatch(20, Duration.ofSeconds(30))
                    .flatMap(takeWorker::process)
                    .then().block(Duration.ofSeconds(60));
            db.sql("UPDATE video_shot_take SET claimed_until=NULL, next_attempt_at=now() "
                    + "WHERE status IN ('queued','submitted','processing')").then().block(Duration.ofSeconds(5));
        }
    }

    /** wanx-v1 在默认价目表有行（80 分/张），免费环估价才能过。 */
    private void seedImageModel() {
        String encrypted = encryptionProvider.getIfAvailable().encrypt("sk-it-anchor-image");
        db.sql("""
                WITH cred AS (
                    INSERT INTO platform_provider_credential(name, provider, base_url,
                        encrypted_key, key_version, masked_hint, enabled)
                    VALUES ('it-anchor-image', 'qwen', :baseUrl, :encrypted, 'v1', 'sk-***ai', true)
                    RETURNING id, base_url
                )
                INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
                    health_status, enabled, version, credential_id)
                SELECT 'image_generation', 'primary', 'qwen', 'wanx-v1', cred.base_url, 'healthy', true, 1, cred.id
                FROM cred
                """)
                .bind("baseUrl", QWEN.baseUrl())
                .bind("encrypted", encrypted)
                .then().block(Duration.ofSeconds(10));
    }

    private void seedVideoModel(String provider, String model) {
        String encrypted = encryptionProvider.getIfAvailable().encrypt("sk-it-anchor-video");
        db.sql("""
                WITH cred AS (
                    INSERT INTO platform_provider_credential(name, provider, base_url,
                        encrypted_key, key_version, masked_hint, enabled)
                    VALUES ('it-anchor-video', :provider, :baseUrl, :encrypted, 'v1', 'sk-***av', true)
                    RETURNING id, base_url
                )
                INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
                    health_status, enabled, version, credential_id)
                SELECT 'video_generation', 'primary', :provider, :model, cred.base_url, 'healthy', true, 1, cred.id
                FROM cred
                """)
                .bind("provider", provider)
                .bind("model", model)
                .bind("baseUrl", QWEN.baseUrl())
                .bind("encrypted", encrypted)
                .then().block(Duration.ofSeconds(10));
    }

    private void stubImageGeneration(String b64) {
        QWEN.stubFor(post(urlEqualTo("/images/generations"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}")));
    }

    private UUID seedStoryboardAndShot(int anchorImageIndex) {
        String payload = "{\"images\":[\"" + PAYLOAD_IMAGE + "\"],\"shopName\":\"店\"}";
        UUID storyboardId = UUID.fromString(db.sql("""
                        INSERT INTO video_storyboard(account_id, target_duration_seconds, request_payload)
                        VALUES (:account, 15, CAST(:payload AS jsonb)) RETURNING id::text
                        """)
                .bind("account", ACCOUNT).bind("payload", payload)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
        return UUID.fromString(db.sql("""
                        INSERT INTO video_shot(storyboard_id, seq, visual, narration, planned_seconds,
                            camera_move, anchor_image_index, prompt)
                        VALUES (CAST(:sb AS uuid), 1, '画面', '旁白', 5, '固定机位', :anchor, '提示词')
                        RETURNING id::text
                        """)
                .bind("sb", storyboardId.toString()).bind("anchor", anchorImageIndex)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }
}
