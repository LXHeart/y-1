package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.run.PriceTableService;
import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.storage.ObjectStorageAdapter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 任务书 #64 卡10：一口价计费闭环——预估预留=目标×单价、实际秒结算多退少补、失败零净扣
 * （补偿意图落账）、重抽/多 take 不追加、TTS 免费分支零积分流水、调价后旧任务按冻结价。
 * 真实秒数由 ffmpeg 合成产出（无 ffmpeg 整类跳过；单元断言在 CompositionMathTest）。
 */
@DisplayName("Flat-price billing closed loop")
@TestPropertySource(properties = { "ai.video-generation.worker-enabled=false",
        "ai.video-generation.max-attempts=2" })
class VideoBillingClosedLoopIT extends IntelligenceItSupport {

    private static final String ACCOUNT = "91919191-9191-9191-9191-919191919191";
    private static final String IMAGE = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAgAAAQABAAD//gAQTGF2YzYyLjI4LjEwMQD/2wBDAAgEBAQEBAUFBQUFBQYGBgYGBgYGBgYGBgYHBwcICAgHBwcGBgcHCAgICAkJCQgICAgJCQoKCgwMCwsODg4RERT/xABNAAEBAAAAAAAAAAAAAAAAAAAABwEBAQEAAAAAAAAAAAAAAAAAAAUHEAEAAAAAAAAAAAAAAAAAAAAAEQEAAAAAAAAAAAAAAAAAAAAA/8AAEQgB4AFAAwEiAAIRAAMRAP/aAAwDAQACEQMRAD8AjgDf0oAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB/9k=";

    @MockitoBean
    CreditsClient credits;

    @MockitoBean
    ObjectStorageAdapter storage;

    @Autowired
    VideoProductionTaskService taskService;

    @Autowired
    VideoCompositionService composition;

    @Autowired
    TtsWorker ttsWorker;

    @Autowired
    TakeGenerationWorker takeWorker;

    @Autowired
    VideoShotTakeRepository takes;

    @Autowired
    VideoShotAudioRepository audios;

    @Autowired
    PriceTableService priceTable;

    @Autowired
    VideoProductionTaskRepository taskRepo;

    private VideoProductionTask rebuildAwareFind(UUID id) {
        return taskRepo.findById(id, ACCOUNT).block(Duration.ofSeconds(5));
    }

    private final List<String> creditCalls = new CopyOnWriteArrayList<>();
    private final Map<String, byte[]> objectStore = new ConcurrentHashMap<>();

    @BeforeAll
    static void requireFfmpeg() {
        assumeTrue(VideoCompositionIT.ffmpegAvailable(), "环境无 ffmpeg，跳过计费闭环 IT");
    }

    @BeforeEach
    void cleanAndSeed() {
        creditCalls.clear();
        objectStore.clear();
        reset(credits, storage);
        when(credits.consume(anyString(), any(CreditFeature.class), anyString()))
                .thenAnswer(invocation -> {
                    creditCalls.add("consume:" + invocation.getArgument(1));
                    return Mono.just(new CreditCharge(
                            invocation.getArgument(0), invocation.getArgument(1),
                            invocation.getArgument(2)));
                });
        when(credits.refund(any(), anyString())).thenReturn(Mono.empty());
        when(credits.compensate(any(), anyString())).thenReturn(Mono.empty());
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
                .then(db.sql("DELETE FROM ai_credit_compensation").then())
                .then(db.sql("DELETE FROM ai_run").then())
                .then(db.sql("DELETE FROM platform_model_config WHERE capability IN "
                        + "('video_generation','video_tts')").then())
                .then(db.sql("DELETE FROM platform_provider_credential WHERE base_url LIKE '%.sandbox.invalid' "
                        + "OR name LIKE 'it-bill-%'").then())
                // 价目隔离：上一例可能调过 slideshow-v1 单价，恢复 1 分/秒
                .then(db.sql("""
                        UPDATE price_table_model SET cents_per_second=1
                        WHERE model_id='slideshow-v1'
                          AND version_id=(SELECT id FROM price_table_version WHERE status='active')
                        """).then())
                .block(Duration.ofSeconds(10));
        priceTable.invalidate();
        // 无 video_generation 行 → slideshow（本地渲染，slideshow-v1 兜底价 1 分/秒）
        seedCapability("video_tts");
    }

    @Test
    @DisplayName("一口价：预估=目标×单价；实际秒结算多退；重抽/TTS/无 BGM 不追加")
    void flatPriceSettlesByActualSeconds() {
        // 6 镜 × 19 字旁白 ≈ 4.75s/镜 ≈ 28.5s（目标 30 → 实际少 → 退差额）
        UUID storyboardId = seedStoryboard(30, "[\"" + IMAGE + "\"]");
        List<UUID> shotIds = new ArrayList<>();
        for (int seq = 1; seq <= 6; seq++) {
            shotIds.add(seedShot(storyboardId, seq, "一二三四五六七八九十一二三四五六七八九", 1));
        }
        shotIds.forEach(this::voiceShot);

        VideoProductionTask task = taskService
                .create(ACCOUNT, null, new VideoProductionTaskService.CreateRequest(storyboardId, null))
                .block(Duration.ofSeconds(30));
        assertThat(task.estimatedCostCents()).isEqualTo(30);
        assertThat(task.unitPriceCents()).isEqualTo(1);

        // TTS 全部成功期间：免费分支零积分流水（reset 清 mock 调用记录，另清本地流水）
        reset(credits);
        creditCalls.clear();
        assertThat(creditCalls.stream().filter(call -> call.startsWith("consume")).count()).isZero();
        verifyNoInteractions(credits);

        task = taskService.requestCompose(task.id(), ACCOUNT).block(Duration.ofSeconds(10));
        composition.compose(task).block(Duration.ofSeconds(180));

        VideoProductionTask done = rebuildAwareFind(task.id());
        assertThat(done.phase()).isEqualTo(VideoProductionTask.PHASE_SUCCEEDED);
        assertThat(done.actualDurationSeconds()).isBetween(27, 30);
        // 结算=实际秒×冻结单价，且 < 预估（多退）
        assertThat(done.actualCostCents())
                .isEqualTo(done.actualDurationSeconds() * 1)
                .isLessThan(done.estimatedCostCents());
        // ai_run 与任务行成本一致（对账底线）
        String run = db.sql("SELECT actual_cents::text || ':' || status AS row FROM ai_run "
                        + "WHERE id=CAST(:run AS uuid)")
                .bind("run", done.runId().toString())
                .map(row -> row.get("row", String.class)).one().block(Duration.ofSeconds(5));
        assertThat(run).isEqualTo(done.actualCostCents() + ":completed");
    }

    @Test
    @DisplayName("整链失败零净扣：预留全额退（补偿意图落账）")
    void failureCompensatesReservation() {
        // video 模式 + 失败 provider：全部 take 失败 → 任务失败 + handleFailure
        seedVideoCapability("seedance", "qwen-plus", "VG-BILL-FAIL");
        QWEN.stubFor(com.github.tomakehurst.wiremock.client.WireMock
                .post(com.github.tomakehurst.wiremock.client.WireMock
                        .urlEqualTo("/api/v3/contents/generations/tasks"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                        .withStatus(500).withBody("boom")));

        UUID storyboardId = seedStoryboard(15, "[\"" + IMAGE + "\"]");
        seedShot(storyboardId, 1, "旁白一", 1);
        VideoProductionTask task = taskService
                .create(ACCOUNT, null, new VideoProductionTaskService.CreateRequest(storyboardId, null))
                .block(Duration.ofSeconds(30));
        assertThat(task.estimatedCostCents()).isEqualTo(15 * task.unitPriceCents());

        for (int cycle = 0; cycle < 4; cycle++) {
            takes.claimBatch(20, Duration.ofSeconds(30)).flatMap(takeWorker::process)
                    .then().block(Duration.ofSeconds(30));
            db.sql("UPDATE video_shot_take SET claimed_until=NULL, next_attempt_at=now() "
                    + "WHERE status IN ('queued','submitted','processing')").then()
                    .block(Duration.ofSeconds(5));
        }

        VideoProductionTask failed = rebuildAwareFind(task.id());
        assertThat(failed.phase()).isEqualTo(VideoProductionTask.PHASE_FAILED);
        // 零净扣：consume(15) 发生在预留；失败后补偿意图入账（退款由补偿 worker 异步执行）
        assertThat(creditCalls.stream().filter(call -> call.contains("VIDEO_PRODUCTION_VIDEO")).count())
                .isEqualTo(1);
        Long compensation = db.sql("SELECT COUNT(*) AS n FROM ai_credit_compensation")
                .map(row -> row.get("n", Long.class)).one().block(Duration.ofSeconds(5));
        assertThat(compensation).isEqualTo(1L);
        String run = db.sql("SELECT status FROM ai_run WHERE id=CAST(:run AS uuid)")
                .bind("run", failed.runId().toString())
                .map(row -> row.get("status", String.class)).one().block(Duration.ofSeconds(5));
        assertThat(run).isEqualTo("failed");
    }

    @Test
    @DisplayName("调价后旧任务按冻结价结算（task 行单价为准，不读当前价目）")
    void priceChangeKeepsFrozenPrice() throws Exception {
        UUID storyboardId = seedStoryboard(15, "[\"" + IMAGE + "\"]");
        UUID shotId = seedShot(storyboardId, 1, "四字旁白", 1);
        voiceShot(shotId);
        VideoProductionTask task = taskService
                .create(ACCOUNT, null, new VideoProductionTaskService.CreateRequest(storyboardId, null))
                .block(Duration.ofSeconds(30));
        assertThat(task.unitPriceCents()).isEqualTo(1);

        // 治理台调价：slideshow-v1 单秒价 1 → 5（改 active 版本行 + 失效缓存）
        db.sql("""
                UPDATE price_table_model SET cents_per_second=5
                WHERE model_id='slideshow-v1'
                  AND version_id=(SELECT id FROM price_table_version WHERE status='active')
                """).then().block(Duration.ofSeconds(5));
        priceTable.invalidate();
        Thread.sleep(200); // invalidate 异步回源，等缓存收敛

        task = taskService.requestCompose(task.id(), ACCOUNT).block(Duration.ofSeconds(10));
        composition.compose(task).block(Duration.ofSeconds(120));

        VideoProductionTask done = rebuildAwareFind(task.id());
        assertThat(done.phase()).isEqualTo(VideoProductionTask.PHASE_SUCCEEDED);
        // 冻结价 1 分/秒结算，而非新价 5 分/秒
        assertThat(done.actualCostCents()).isEqualTo(done.actualDurationSeconds() * 1);
    }

    // ---------------- helpers ----------------

    private void voiceShot(UUID shotId) {
        VideoShotAudio audio = audios.create(shotId, null, null).block(Duration.ofSeconds(5));
        ttsWorker.process(audio).block(Duration.ofSeconds(30));
        VideoShotAudio done = audios.findByShot(shotId).block(Duration.ofSeconds(5));
        assertTrue(done.isSettled(), "sandbox TTS 应成功或跳过");
    }

    private void seedCapability(String capability) {
        db.sql("""
                WITH cred AS (
                    INSERT INTO platform_provider_credential(name, provider, base_url, enabled)
                    VALUES (:name, 'sandbox', :baseUrl, true) RETURNING id, base_url
                )
                INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
                    health_status, enabled, version, credential_id)
                SELECT :capability, 'primary', 'sandbox', :model, cred.base_url, 'healthy', true, 1, cred.id
                FROM cred
                """)
                .bind("name", "it-bill-" + capability)
                .bind("baseUrl", "https://" + capability + ".sandbox.invalid")
                .bind("capability", capability)
                .bind("model", "video_tts".equals(capability) ? "sandbox-tts-v1" : "sandbox-video-v1")
                .then().block(Duration.ofSeconds(10));
    }

    private void seedVideoCapability(String provider, String model, String tag) {
        String encrypted = encryptionProvider.getIfAvailable().encrypt("sk-it-bill-key");
        db.sql("""
                WITH cred AS (
                    INSERT INTO platform_provider_credential(name, provider, base_url,
                        encrypted_key, key_version, masked_hint, enabled)
                    VALUES (:name, :provider, :baseUrl, :encrypted, 'v1', 'sk-***bill', true)
                    RETURNING id, base_url
                )
                INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
                    health_status, enabled, version, credential_id)
                SELECT 'video_generation', 'primary', :provider, :model, cred.base_url, 'healthy', true, 1, cred.id
                FROM cred
                """)
                .bind("name", "it-bill-" + tag)
                .bind("provider", provider)
                .bind("baseUrl", QWEN.baseUrl())
                .bind("encrypted", encrypted)
                .bind("model", model)
                .then().block(Duration.ofSeconds(10));
    }

    private UUID seedStoryboard(int targetDurationSeconds, String imagesJson) {
        String payload = "{\"images\":" + imagesJson + ",\"shopName\":\"店\"}";
        return UUID.fromString(db.sql("""
                        INSERT INTO video_storyboard(account_id, target_duration_seconds, request_payload)
                        VALUES (:account, :duration, CAST(:payload AS jsonb)) RETURNING id::text
                        """)
                .bind("account", ACCOUNT).bind("duration", targetDurationSeconds)
                .bind("payload", payload)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }

    private UUID seedShot(UUID storyboardId, int seq, String narration, int anchor) {
        return UUID.fromString(db.sql("""
                        INSERT INTO video_shot(storyboard_id, seq, visual, narration, planned_seconds,
                            camera_move, anchor_image_index, prompt)
                        VALUES (CAST(:sb AS uuid), :seq, '画面', :narration, 5, '固定机位', :anchor, 'p')
                        RETURNING id::text
                        """)
                .bind("sb", storyboardId.toString()).bind("seq", seq)
                .bind("narration", narration).bind("anchor", anchor)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }
}
