package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.mediaplatform.MediaProcessRunner;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Mono;

/**
 * 任务书 #65 卡6：成片后单镜重抽 + 段缓存重合成。
 * 断言：reroll 状态机与闸、仅变更镜重渲染（其余段对象零重写、ffmpeg 次数收敛）、
 * 重合成两次结算按同一幂等键（确定性 operationId）重放不重复扣。
 */
@DisplayName("Video reroll and segment-cached recompose")
@TestPropertySource(properties = { "ai.video-generation.worker-enabled=false" })
class VideoRerollSegmentCacheIT extends IntelligenceItSupport {

    private static final String ACCOUNT = "94949494-9494-9494-9494-949494949494";
    private static final String IMAGE = "data:image/png;base64,QUFB";

    @MockitoBean
    CreditsClient credits;

    @MockitoBean
    ObjectStorageAdapter storage;

    /** ffmpeg 调用计数（段渲染 + 终段 concat 都走 runner）。 */
    @MockitoSpyBean
    MediaProcessRunner runner;

    @Autowired
    TakeGenerationWorker takeWorker;

    @Autowired
    VideoCompositionService composition;

    @Autowired
    VideoProductionTaskService taskService;

    @Autowired
    VideoShotTakeRepository takes;

    @Autowired
    VideoProductionTaskRepository tasks;

    private final Map<String, byte[]> objectStore = new ConcurrentHashMap<>();
    private final List<String> segmentWrites = new CopyOnWriteArrayList<>();
    private final List<String> consumeOperationIds = new CopyOnWriteArrayList<>();

    @BeforeEach
    void cleanAndSeed() {
        reset(credits, storage);
        when(credits.consume(anyString(), any(CreditFeature.class), anyString()))
                .thenAnswer(invocation -> {
                    consumeOperationIds.add(invocation.getArgument(2));
                    return Mono.just(new CreditCharge(invocation.getArgument(0),
                            invocation.getArgument(1), invocation.getArgument(2)));
                });
        when(credits.reserveUsage(anyString(), any(CreditFeature.class), anyString(), anyLong(), any()))
                .thenAnswer(invocation -> Mono.just(new CreditCharge(invocation.getArgument(0),
                        invocation.getArgument(1), invocation.getArgument(2))));
        when(credits.refund(any(), anyString())).thenReturn(Mono.empty());
        objectStore.clear();
        segmentWrites.clear();
        consumeOperationIds.clear();
        Mockito.doAnswer(invocation -> {
            Object rawKey = invocation.getArgument(0);
            Object rawContent = invocation.getArgument(1);
            if (!(rawKey instanceof String key) || !(rawContent instanceof byte[] content)) {
                throw new IllegalStateException("putObject 参数类型异常: key=" + rawKey.getClass()
                        + " content=" + rawContent.getClass());
            }
            objectStore.put(key, content);
            if (key.startsWith("segments/")) {
                segmentWrites.add(key);
            }
            return null;
        }).when(storage).putObject(anyString(), any(byte[].class), anyString());
        when(storage.getObject(anyString()))
                .thenAnswer(invocation -> objectStore.get(invocation.getArgument(0)));
        when(storage.presignDownload(anyString(), any(Long.class)))
                .thenAnswer(invocation -> java.net.URI.create("https://media.example.test/signed"));
        Mockito.doAnswer(invocation -> objectStore.keySet().stream()
                .filter(key -> key.startsWith(String.valueOf(invocation.getArgument(0))))
                .map(key -> new com.grassland.storage.StoredObject(key, 1, null, null,
                        java.time.Instant.now()))
                .toList())
                .when(storage).listObjects(anyString());
        Mockito.doAnswer(invocation -> {
            objectStore.remove(invocation.getArgument(0));
            return null;
        }).when(storage).deleteObject(anyString());

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
        seedVideoCapability();
    }

    @Test
    @DisplayName("reroll 闸：非 succeeded 409；成功重抽软删旧候选、回 generating、recompose_seq+1")
    void rerollGuardsAndStateTransitions() {
        UUID storyboardId = seedStoryboardAndShots(1);
        UUID shot1 = shotId(storyboardId, 1);
        VideoProductionTask task = taskService
                .create(ACCOUNT, null, new VideoProductionTaskService.CreateRequest(storyboardId, "op-reroll-1"))
                .block(Duration.ofSeconds(20));

        // 未完成 → 409
        client().post().uri("/api/video-production/tasks/{id}/shots/{shotId}/reroll", task.id(), shot1)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isEqualTo(409);

        driveAllTakes(storyboardId);
        taskService.requestCompose(task.id(), ACCOUNT).block(Duration.ofSeconds(10));
        composition.compose(tasks.findById(task.id(), ACCOUNT).block(Duration.ofSeconds(5)))
                .block(Duration.ofSeconds(120));
        assertThat(tasks.findById(task.id(), ACCOUNT).block(Duration.ofSeconds(5)).phase())
                .isEqualTo(VideoProductionTask.PHASE_SUCCEEDED);

        // 成片后重抽 → 202 + 新候选；旧候选 cancelled；phase 回 generating
        UUID oldTake = takes.findByShot(shot1).collectList().block().getFirst().id();
        client().post().uri("/api/video-production/tasks/{id}/shots/{shotId}/reroll", task.id(), shot1)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.data.takes.length()").isEqualTo(2)
                .jsonPath("$.data.takes[0].status").isEqualTo("queued");

        VideoShotTake softDeleted = takes.findByShot(shot1).collectList().block().stream()
                .filter(take -> take.id().equals(oldTake)).findFirst().orElseThrow();
        assertThat(softDeleted.status()).isEqualTo(VideoShotTake.STATUS_CANCELLED);
        VideoProductionTask reopened = tasks.findById(task.id(), ACCOUNT).block(Duration.ofSeconds(5));
        assertThat(reopened.phase()).isEqualTo(VideoProductionTask.PHASE_GENERATING);
        assertThat(reopened.recomposeSeq()).isEqualTo(1);
    }

    @Test
    @DisplayName("段缓存重合成：仅变更镜重渲染（其余段零重写）、ffmpeg 收敛、结算幂等键确定性")
    void rerollRecomposesOnlyChangedShotWithIdempotentSettlement() throws Exception {
        UUID storyboardId = seedStoryboardAndShots(2);
        UUID shot1 = shotId(storyboardId, 1);
        UUID shot2 = shotId(storyboardId, 2);
        VideoProductionTask task = taskService
                .create(ACCOUNT, null, new VideoProductionTaskService.CreateRequest(storyboardId, "op-reroll-2"))
                .block(Duration.ofSeconds(20));
        driveAllTakes(storyboardId);
        taskService.requestCompose(task.id(), ACCOUNT).block(Duration.ofSeconds(10));
        composition.compose(tasks.findById(task.id(), ACCOUNT).block(Duration.ofSeconds(5)))
                .block(Duration.ofSeconds(120));
        VideoProductionTask first = tasks.findById(task.id(), ACCOUNT).block(Duration.ofSeconds(5));
        assertThat(first.phase()).isEqualTo(VideoProductionTask.PHASE_SUCCEEDED);
        assertThat(first.recomposeSeq()).isZero();
        // 首次合成：每镜段落 + 指纹都已入缓存
        assertThat(segmentWrites.stream().filter(key -> key.endsWith(".mp4")).count()).isEqualTo(2);

        // 重抽第 1 镜 → 重合成
        taskService.reroll(task.id(), ACCOUNT, shot1).block(Duration.ofSeconds(10));
        driveAllTakes(storyboardId);
        taskService.requestCompose(task.id(), ACCOUNT).block(Duration.ofSeconds(10));

        int ffmpegBefore = ffmpegCalls();
        segmentWrites.clear();
        composition.compose(tasks.findById(task.id(), ACCOUNT).block(Duration.ofSeconds(5)))
                .block(Duration.ofSeconds(120));

        VideoProductionTask recomposed = tasks.findById(task.id(), ACCOUNT).block(Duration.ofSeconds(5));
        assertThat(recomposed.phase()).isEqualTo(VideoProductionTask.PHASE_SUCCEEDED);
        assertThat(recomposed.recomposeSeq()).isEqualTo(1);
        // 结算回填：新实际秒 × 单价
        assertThat(recomposed.actualCostCents())
                .isEqualTo(recomposed.actualDurationSeconds() * recomposed.unitPriceCents());

        // 仅第 1 镜的段重写；第 2 镜段零重写（缓存命中）
        assertThat(segmentWrites.stream().filter(key -> key.endsWith(".mp4")).count()).isEqualTo(1);
        assertThat(segmentWrites).noneMatch(key -> key.contains(shot2.toString()));
        // ffmpeg 收敛：1 个段重渲染 + 1 个终段 concat（无字幕烧录差异时 final 也是一次调用）
        assertThat(ffmpegCalls() - ffmpegBefore).isEqualTo(2);

        // 结算幂等：重放同一 recompose（模拟 settle 后未及收口的重试）→ 同一确定性 operationId
        String recomposeOp = VideoCompositionService.recomposeOperationId(task.id(), 1).toString();
        List<String> recomposeConsumes = new ArrayList<>(consumeOperationIds);
        assertThat(recomposeConsumes).contains(recomposeOp);

        db.sql("UPDATE video_production_task SET phase='composing',claimed_until=NULL,claim_token=NULL "
                        + "WHERE id=CAST(:id AS uuid)")
                .bind("id", task.id().toString()).then().block(Duration.ofSeconds(5));
        composition.compose(tasks.findById(task.id(), ACCOUNT).block(Duration.ofSeconds(5)))
                .block(Duration.ofSeconds(120));
        assertThat(tasks.findById(task.id(), ACCOUNT).block(Duration.ofSeconds(5)).phase())
                .isEqualTo(VideoProductionTask.PHASE_SUCCEEDED);

        ArgumentCaptor<String> opCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(credits, atLeastOnce())
                .consume(anyString(), any(CreditFeature.class), opCaptor.capture());
        long recomposeCharges = opCaptor.getAllValues().stream()
                .filter(recomposeOp::equals).count();
        // 两次结算都命中同一 operationId——finance 按 consumeOperationId 幂等去重（不重复扣）
        assertThat(recomposeCharges).isEqualTo(2);
        assertThat(VideoCompositionService.recomposeOperationId(task.id(), 1))
                .isEqualTo(VideoCompositionService.recomposeOperationId(task.id(), 1));
        assertThat(VideoCompositionService.recomposeOperationId(task.id(), 1))
                .isNotEqualTo(VideoCompositionService.recomposeOperationId(task.id(), 2));
    }

    // ---------------- helpers ----------------

    private int ffmpegCalls() {
        return Mockito.mockingDetails(runner).getInvocations().stream()
                .filter(invocation -> "ffmpeg".equals(invocation.getMethod().getName()))
                .mapToInt(ignored -> 1).sum();
    }

    private UUID shotId(UUID storyboardId, int seq) {
        return UUID.fromString(db.sql("SELECT id::text FROM video_shot "
                        + "WHERE storyboard_id=CAST(:sb AS uuid) AND seq=:seq")
                .bind("sb", storyboardId.toString()).bind("seq", seq)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
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

    private void seedVideoCapability() {
        db.sql("""
                WITH cred AS (
                    INSERT INTO platform_provider_credential(name, provider, base_url, enabled)
                    VALUES ('it-reroll-video', 'sandbox', 'https://reroll.sandbox.invalid', true)
                    RETURNING id, base_url
                )
                INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
                    health_status, enabled, version, credential_id)
                SELECT 'video_generation', 'primary', 'sandbox', 'sandbox-video-v1', cred.base_url,
                    'healthy', true, 1, cred.id
                FROM cred
                """)
                .then().block(Duration.ofSeconds(10));
    }

    private UUID seedStoryboardAndShots(int shotCount) {
        String payload = "{\"images\":[\"" + IMAGE + "\"],\"shopName\":\"店\"}";
        UUID storyboardId = UUID.fromString(db.sql("""
                        INSERT INTO video_storyboard(account_id, target_duration_seconds, request_payload)
                        VALUES (:account, 15, CAST(:payload AS jsonb)) RETURNING id::text
                        """)
                .bind("account", ACCOUNT).bind("payload", payload)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
        for (int seq = 1; seq <= shotCount; seq++) {
            db.sql("""
                            INSERT INTO video_shot(storyboard_id, seq, visual, narration, planned_seconds,
                                camera_move, anchor_image_index, prompt)
                            VALUES (CAST(:sb AS uuid), :seq, '画面', '旁白', 5, '固定机位', 1, '提示词')
                            """)
                    .bind("sb", storyboardId.toString()).bind("seq", seq)
                    .then().block(Duration.ofSeconds(5));
        }
        return storyboardId;
    }
}
