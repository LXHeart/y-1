package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * 任务书 #65 卡4：任务 SSE 事件流——take/phase/heartbeat 帧、compose 进度与终态收口、
 * 断流（提前 dispose）不影响任务完成。心跳间隔压到 1s（IT 可见）。
 */
@DisplayName("Video task SSE event stream")
@TestPropertySource(properties = { "ai.video-generation.worker-enabled=false",
        "ai.video-production.sse-heartbeat-seconds=1" })
class VideoTaskEventStreamIT extends IntelligenceItSupport {

    private static final String ACCOUNT = "93939393-9393-9393-9393-939393939393";
    private static final String IMAGE = "data:image/png;base64,QUFB";

    @MockitoBean
    CreditsClient credits;

    @MockitoBean
    ObjectStorageAdapter storage;

    @Autowired
    TakeGenerationWorker takeWorker;

    @Autowired
    TtsWorker ttsWorker;

    @Autowired
    VideoCompositionService composition;

    @Autowired
    VideoProductionTaskService taskService;

    @Autowired
    VideoShotTakeRepository takes;

    @Autowired
    VideoShotAudioRepository audios;

    @Autowired
    VideoProductionTaskRepository tasks;

    private final java.util.Map<String, byte[]> objectStore = new java.util.concurrent.ConcurrentHashMap<>();

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
                .then(db.sql("DELETE FROM ai_run").then())
                .then(db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN "
                        + "(SELECT id FROM platform_model_config WHERE capability IN "
                        + "('video_generation','video_tts'))").then())
                .then(db.sql("DELETE FROM platform_model_config WHERE capability IN "
                        + "('video_generation','video_tts')").then())
                .then(db.sql("DELETE FROM platform_provider_credential WHERE base_url LIKE '%.sandbox.invalid'").then())
                .block(Duration.ofSeconds(10));
        seedCapability("video_generation", "sandbox", "sandbox-video-v1");
        seedCapability("video_tts", "sandbox", "sandbox-tts-v1");
    }

    @Test
    @DisplayName("SSE 收到 take/selecting/heartbeat/compose 帧，终态后流收口")
    void sseDeliversTaskFrames() {
        UUID storyboardId = seedStoryboardAndShot();
        VideoProductionTask task = taskService
                .create(ACCOUNT, null, new VideoProductionTaskService.CreateRequest(storyboardId, "op-sse"))
                .block(Duration.ofSeconds(20));

        List<String> frames = new CopyOnWriteArrayList<>();
        Disposable subscription = sse(task.id()).subscribe(frames::add);

        driveAllTakes(storyboardId);
        driveAllAudios(storyboardId);
        awaitFrame(frames, frame -> frame.contains("\"type\":\"take\"") && frame.contains("\"status\":\"succeeded\""));
        awaitFrame(frames, frame -> frame.contains("heartbeat"));
        awaitFrame(frames, frame -> frame.contains("\"phase\":\"selecting\""));

        taskService.requestCompose(task.id(), ACCOUNT).block(Duration.ofSeconds(10));
        composition.compose(tasks.findById(task.id(), ACCOUNT).block(Duration.ofSeconds(5)))
                .block(Duration.ofSeconds(120));
        awaitFrame(frames, frame -> frame.contains("\"phase\":\"composing\""));
        awaitFrame(frames, frame -> frame.contains("compose_progress"));
        awaitFrame(frames, frame -> frame.contains("\"phase\":\"succeeded\""));

        VideoProductionTask done = tasks.findById(task.id(), ACCOUNT).block(Duration.ofSeconds(5));
        assertThat(done.phase()).isEqualTo(VideoProductionTask.PHASE_SUCCEEDED);
        subscription.dispose();
    }

    @Test
    @DisplayName("断流（提前 dispose）不影响任务完成")
    void droppedStreamDoesNotAffectCompletion() {
        UUID storyboardId = seedStoryboardAndShot();
        VideoProductionTask task = taskService
                .create(ACCOUNT, null, new VideoProductionTaskService.CreateRequest(storyboardId, "op-sse-drop"))
                .block(Duration.ofSeconds(20));

        List<String> frames = new CopyOnWriteArrayList<>();
        Disposable subscription = sse(task.id()).subscribe(frames::add);
        subscription.dispose();

        driveAllTakes(storyboardId);
        driveAllAudios(storyboardId);
        taskService.requestCompose(task.id(), ACCOUNT).block(Duration.ofSeconds(10));
        composition.compose(tasks.findById(task.id(), ACCOUNT).block(Duration.ofSeconds(5)))
                .block(Duration.ofSeconds(120));

        VideoProductionTask done = tasks.findById(task.id(), ACCOUNT).block(Duration.ofSeconds(5));
        assertThat(done.phase()).isEqualTo(VideoProductionTask.PHASE_SUCCEEDED);
        assertThat(done.finalMediaId()).isNotNull();
    }

    @Test
    @DisplayName("未登录 401；非属主 404")
    void authAndOwnershipGates() {
        UUID storyboardId = seedStoryboardAndShot();
        VideoProductionTask task = taskService
                .create(ACCOUNT, null, new VideoProductionTaskService.CreateRequest(storyboardId, "op-sse-auth"))
                .block(Duration.ofSeconds(20));

        client().get().uri("/api/video-production/tasks/{id}/events", task.id())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange().expectStatus().isUnauthorized();

        client().get().uri("/api/video-production/tasks/{id}/events", task.id())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header("X-Grassland-Identity", sign("8f8f8f8f-8f8f-8f8f-8f8f-8f8f8f8f8f8f", "recommender"))
                .exchange().expectStatus().isNotFound();
    }

    // ---------------- helpers ----------------

    /** SSE 载荷流（去掉 data: 前缀的裸 JSON 行）。 */
    private reactor.core.publisher.Flux<String> sse(UUID taskId) {
        return client().get().uri("/api/video-production/tasks/{id}/events", taskId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange()
                .returnResult(String.class)
                .getResponseBody()
                .map(line -> line.replaceFirst("^data: ", "").trim())
                .filter(line -> !line.isEmpty());
    }

    private static void awaitFrame(List<String> frames, java.util.function.Predicate<String> match) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (frames.stream().anyMatch(match)) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("await interrupted", error);
            }
        }
        throw new AssertionError("SSE 帧未在时限内到达，期待匹配 " + match + "，实收: " + frames);
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

    private void driveAllAudios(UUID storyboardId) {
        audios.findByStoryboard(storyboardId)
                .concatMap(ttsWorker::process)
                .then().block(Duration.ofSeconds(60));
    }

    private UUID seedStoryboardAndShot() {
        String payload = "{\"images\":[\"" + IMAGE + "\"],\"shopName\":\"店\"}";
        UUID storyboardId = UUID.fromString(db.sql("""
                        INSERT INTO video_storyboard(account_id, target_duration_seconds, request_payload)
                        VALUES (:account, 15, CAST(:payload AS jsonb)) RETURNING id::text
                        """)
                .bind("account", ACCOUNT).bind("payload", payload)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
        db.sql("""
                        INSERT INTO video_shot(storyboard_id, seq, visual, narration, planned_seconds,
                            camera_move, anchor_image_index, prompt)
                        VALUES (CAST(:sb AS uuid), 1, '画面', '旁白', 5, '固定机位', 1, '提示词')
                        """)
                .bind("sb", storyboardId.toString())
                .then().block(Duration.ofSeconds(5));
        return storyboardId;
    }

    private void seedCapability(String capability, String provider, String model) {
        db.sql("""
                WITH cred AS (
                    INSERT INTO platform_provider_credential(name, provider, base_url, enabled)
                    VALUES (:name, :provider, :baseUrl, true)
                    RETURNING id, base_url
                )
                INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
                    health_status, enabled, version, credential_id)
                SELECT :capability, 'primary', :provider, :model, cred.base_url, 'healthy', true, 1, cred.id
                FROM cred
                """)
                .bind("name", "it-sse-" + capability)
                .bind("capability", capability)
                .bind("provider", provider)
                .bind("baseUrl", "https://" + capability + ".sandbox.invalid")
                .bind("model", model)
                .then().block(Duration.ofSeconds(10));
    }
}
