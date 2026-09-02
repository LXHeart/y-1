package com.grassland.intelligence.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.videoproduction.VideoProductionTask;
import com.grassland.intelligence.videoproduction.VideoProductionTaskRepository;
import com.grassland.storage.ObjectStorageAdapter;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeAll;
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
 * 卡A1 Temporal 路全链 IT：开关 temporal（内存 test server + 上下文内真 worker/真 activity），
 * sandbox 渠道（take=testsrc mp4、TTS=正弦波 wav）走 建流→生成→选片信号→合成→结算，
 * 收口断言 workflow 终态与行一致 + 双写对账零差异。legacy 路回归由既有 #64/#65 IT 覆盖
 * （默认开关 legacy，本类独立 context 不外溢）。
 */
@DisplayName("Video production temporal orchestration chain")
@TestPropertySource(properties = {
        "ai.video-production.orchestration=temporal",
        "ai.video-generation.poll-interval=500ms"
})
class VideoProductionTemporalIT extends IntelligenceItSupport {

    private static final String ACCOUNT = "62626262-6262-6262-6262-626262626262";
    private static final String IMAGE = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAgAAAQABAAD//gAQTGF2YzYyLjI4LjEwMQ==";

    @MockitoBean
    CreditsClient credits;

    @MockitoBean
    ObjectStorageAdapter storage;

    @Autowired
    VideoProductionTaskRepository tasks;

    @Autowired
    VideoWorkflowStarter starter;

    @Autowired
    VideoOrchestrationReconciliationWorker reconciliation;

    @Autowired
    VideoOrchestrationAdoptionSweeper adoption;

    private final Map<String, byte[]> objectStore = new ConcurrentHashMap<>();

    @BeforeAll
    static void requireFfmpeg() {
        assumeTrue(ffmpegAvailable(), "环境无 ffmpeg，跳过 temporal 全链 IT");
    }

    static boolean ffmpegAvailable() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version").start();
            process.waitFor();
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException error) {
            return false;
        }
    }

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
        when(storage.presignDownload(anyString(), anyLong()))
                .thenAnswer(invocation -> java.net.URI.create("https://media.example.test/signed"));
        when(storage.presignDownload(anyString(), anyLong(), anyString()))
                .thenAnswer(invocation -> java.net.URI.create("https://media.example.test/signed-att"));

        db.sql("DELETE FROM video_shot_take").then()
                .then(db.sql("DELETE FROM video_shot_audio").then())
                .then(db.sql("DELETE FROM video_production_task").then())
                .then(db.sql("DELETE FROM video_shot").then())
                .then(db.sql("DELETE FROM video_storyboard").then())
                .then(db.sql("DELETE FROM ai_run").then())
                .then(db.sql("DELETE FROM platform_model_config WHERE capability IN "
                        + "('video_generation','video_tts')").then())
                .then(db.sql("DELETE FROM platform_provider_credential WHERE base_url LIKE '%.sandbox.invalid'").then())
                .then(db.sql("DELETE FROM platform_provider_credential WHERE base_url=:baseUrl "
                        + "AND provider='sandbox'").bind("baseUrl", QWEN.baseUrl()).then())
                .block(Duration.ofSeconds(10));
        seedCapability("video_generation", "sandbox", "sandbox-video-v1", QWEN.baseUrl());
        seedCapability("video_tts", "sandbox", "sandbox-tts-v1", "https://video_tts.sandbox.invalid");
    }

    @Test
    @DisplayName("temporal 全链：建流→候选/配音生成→选片信号→合成结算→工作流终态与行一致、对账零差异")
    void temporalFullChainSelectComposeSettle() {
        UUID storyboardId = seedStoryboard(15, "[\"" + IMAGE + "\",\"" + IMAGE + "\"]");
        seedShot(storyboardId, 1, "老王面馆现熬骨汤", 1);
        seedShot(storyboardId, 2, "每天现切这碗面", 2);

        // 建任务：开关 temporal → 控制器起 workflow（video-task-{taskId}）
        client().post().uri("/api/video-production/tasks")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("storyboardId", storyboardId.toString()))
                .exchange().expectStatus().isOk();
        UUID taskId = readCreatedTaskId(storyboardId);
        assertThat(taskId).isNotNull();

        // 生成段由 workflow 驱动（activity 领单推进 sandbox take/tts）
        awaitPhase(taskId, row -> row.phase() != null && !row.isTerminal()
                && allTakesTerminal(row), Duration.ofSeconds(90), "候选/配音生成全终态");
        VideoProductionTask generating = tasks.findById(taskId, ACCOUNT).block(Duration.ofSeconds(5));
        assertThat(generating.phase()).isNotEqualTo(VideoProductionTask.PHASE_FAILED);

        // 选片（信号）+ 合成（行 phase=composing）
        client().post().uri("/api/video-production/tasks/{id}/takes/select", taskId)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("useRecommended", true))
                .exchange().expectStatus().isOk();
        client().post().uri("/api/video-production/tasks/{id}/compose", taskId)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isOk();

        VideoProductionTask done = awaitPhase(taskId, VideoProductionTask::isTerminal,
                Duration.ofSeconds(180), "合成结算终态");
        assertThat(done.phase()).isEqualTo(VideoProductionTask.PHASE_SUCCEEDED);
        assertThat(done.finalMediaId()).isNotNull();
        assertThat(objectStore.get("media/video_master/" + done.id())).isNotNull();

        // workflow 终态与行一致 + 双写对账零差异。
        // 行先于 workflow 收口是固有竞态（activity 落行终态 → 返回后 workflow 才置 done），
        // 这里等 workflow 追平终态再断言，否则对账也会误报 composing≠succeeded。
        VideoTaskState state = awaitWorkflowTerminal(taskId, Duration.ofSeconds(60));
        assertThat(state.stage()).isEqualTo(VideoProductionWorkflowImpl.STAGE_DONE);
        assertThat(reconciliation.runOnce().block(Duration.ofSeconds(30))).isZero();
    }

    @Test
    @DisplayName("收养清扫（卡A4）：legacy 期存量非终态任务被幂等补起 workflow")
    void adoptionSweeperAdoptsLegacyInFlightTask() {
        UUID storyboardId = seedStoryboard(15, "[\"" + IMAGE + "\",\"" + IMAGE + "\"]");
        seedShot(storyboardId, 1, "老王面馆现熬骨汤", 1);
        // 直插 legacy 期任务行（phase=generating，无 workflow 在岗）
        UUID taskId = UUID.fromString(db.sql("""
                        INSERT INTO video_production_task(storyboard_id, account_id, operation_id, mode,
                            phase, progress, target_duration_seconds, pricing_version, unit_price_cents,
                            estimated_cost_cents)
                        VALUES (CAST(:sb AS uuid), :account, :operation, 'video', 'generating', 10, 15,
                            'v1', 1, 15)
                        RETURNING id::text
                        """)
                .bind("sb", storyboardId.toString())
                .bind("account", ACCOUNT)
                .bind("operation", "adopt-it-" + storyboardId)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
        assertThat(starter.queryState(VideoWorkflowStarter.workflowId(taskId))).isEmpty();

        assertThat(adoption.runOnce().block(Duration.ofSeconds(30))).isGreaterThanOrEqualTo(1L);
        assertThat(starter.queryState(VideoWorkflowStarter.workflowId(taskId))).isPresent();

        // 幂等：再扫一遍不炸（AlreadyStarted 吞掉），对账不误报
        adoption.runOnce().block(Duration.ofSeconds(30));
        assertThat(reconciliation.runOnce().block(Duration.ofSeconds(30))).isZero();
    }

    private static boolean isTerminalStage(String stage) {
        return VideoProductionWorkflowImpl.STAGE_DONE.equals(stage)
                || VideoProductionWorkflowImpl.STAGE_CANCELLED.equals(stage)
                || VideoProductionWorkflowImpl.STAGE_SELECTION_TIMEOUT.equals(stage);
    }

    private VideoTaskState awaitWorkflowTerminal(UUID taskId, Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        VideoTaskState state = starter.queryState(VideoWorkflowStarter.workflowId(taskId)).orElse(null);
        while (state == null || !isTerminalStage(state.stage())) {
            if (System.nanoTime() >= deadline) {
                assertThat(false).as("等待 workflow 终态超时，当前 stage="
                        + (state == null ? "absent" : state.stage())).isTrue();
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            state = starter.queryState(VideoWorkflowStarter.workflowId(taskId)).orElse(null);
        }
        return state;
    }

    // ---------------- helpers ----------------

    private boolean allTakesTerminal(VideoProductionTask task) {
        Long pending = db.sql("SELECT COUNT(*) AS n FROM video_shot_take t "
                        + "JOIN video_shot s ON s.id=t.shot_id "
                        + "WHERE s.storyboard_id=CAST(:sb AS uuid) AND t.status NOT IN "
                        + "('succeeded','failed','cancelled')")
                .bind("sb", task.storyboardId().toString())
                .map(row -> row.get("n", Long.class)).one().block(Duration.ofSeconds(5));
        return pending != null && pending == 0;
    }

    private UUID readCreatedTaskId(UUID storyboardId) {
        return UUID.fromString(db.sql("SELECT id::text AS id FROM video_production_task "
                        + "WHERE storyboard_id=CAST(:sb AS uuid) ORDER BY created_at DESC LIMIT 1")
                .bind("sb", storyboardId.toString())
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }

    private VideoProductionTask awaitPhase(UUID taskId,
            java.util.function.Predicate<VideoProductionTask> condition, Duration budget, String what) {
        long deadline = System.nanoTime() + budget.toNanos();
        VideoProductionTask row = tasks.findById(taskId, ACCOUNT).block(Duration.ofSeconds(5));
        while (!condition.test(row)) {
            if (System.nanoTime() >= deadline) {
                dumpThreadStacks(what);
                dumpVideoRows(taskId);
                assertThat(false).as("等待超时: " + what + "，当前 phase=" + row.phase()).isTrue();
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            row = tasks.findById(taskId, ACCOUNT).block(Duration.ofSeconds(5));
        }
        return row;
    }

    /** 超时诊断：全线程栈转储 + take/audio 行状态（定位 activity 挂停车点）。 */
    private static void dumpThreadStacks(String what) {
        System.out.println("===== THREAD DUMP on timeout: " + what + " =====");
        Thread.getAllStackTraces().forEach((thread, stacks) -> {
            StringBuilder sb = new StringBuilder("\"" + thread.getName() + "\" state=" + thread.getState());
            for (StackTraceElement el : stacks) {
                sb.append("\n    at ").append(el);
            }
            System.out.println(sb);
        });
        System.out.println("===== END THREAD DUMP =====");
    }

    private void dumpVideoRows(UUID taskId) {
        db.sql("""
                SELECT t.status, t.attempts, t.next_attempt_at::text AS next_at,
                    t.claimed_until::text AS lease, t.error_code
                FROM video_shot_take t JOIN video_shot s ON s.id=t.shot_id
                JOIN video_production_task v ON v.storyboard_id=s.storyboard_id
                WHERE v.id=CAST(:id AS uuid)
                """).bind("id", taskId.toString())
                .map(row -> row.get("status", String.class) + " attempts=" + row.get("attempts", Integer.class)
                        + " next=" + row.get("next_at", String.class) + " lease=" + row.get("lease", String.class)
                        + " err=" + row.get("error_code", String.class))
                .all().doOnNext(line -> System.out.println("[take-row] " + line)).then().block(Duration.ofSeconds(5));
        db.sql("SELECT status, COUNT(*) AS n FROM video_shot_audio GROUP BY status")
                .map(row -> row.get("status", String.class) + " x" + row.get("n", Long.class))
                .all().doOnNext(line -> System.out.println("[audio-row] " + line)).then().block(Duration.ofSeconds(5));
    }

    private void seedCapability(String capability, String provider, String model, String baseUrl) {
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
                .bind("name", "it-temporal-" + capability)
                .bind("capability", capability)
                .bind("provider", provider)
                .bind("baseUrl", baseUrl)
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
