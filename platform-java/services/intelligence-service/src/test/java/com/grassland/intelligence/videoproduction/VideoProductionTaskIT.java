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
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.credits.InsufficientCreditsException;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

/**
 * 任务书 #64 卡6：成片任务全链——建任务（幂等/冻结计价/402 收口）、take worker（sandbox 多镜
 * 多 take、锚定图映射、失败重试到限退款）、选片/重抽/取消、历史分页。
 */
@DisplayName("Video production task chain")
@TestPropertySource(properties = { "ai.video-generation.worker-enabled=false",
        "ai.video-generation.max-attempts=2" })
class VideoProductionTaskIT extends IntelligenceItSupport {

    private static final String ACCOUNT = "61616161-6161-6161-6161-616161616161";
    private static final String IMAGE_1 = "data:image/png;base64,AAAA";
    private static final String IMAGE_2 = "data:image/png;base64,BBBB";

    @MockitoBean
    CreditsClient credits;

    @MockitoBean
    ObjectStorageAdapter storage;

    @Autowired
    VideoProductionTaskService taskService;

    @Autowired
    TakeGenerationWorker takeWorker;

    @Autowired
    VideoShotTakeRepository takes;

    @Autowired
    VideoShotAudioRepository audios;

    @Autowired
    VideoProductionTaskRepository tasks;

    private final List<String> storedKeys = new CopyOnWriteArrayList<>();

    @BeforeEach
    void cleanAndSeed() {
        reset(credits, storage);
        when(credits.consume(anyString(), any(CreditFeature.class), anyString()))
                .thenAnswer(invocation -> Mono.just(new CreditCharge(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2))));
        when(credits.refund(any(), anyString())).thenReturn(Mono.empty());
        storedKeys.clear();
        Mockito.doAnswer(invocation -> {
            storedKeys.add(invocation.getArgument(0));
            return null;
        }).when(storage).putObject(anyString(), any(byte[].class), anyString());
        when(storage.presignDownload(anyString(), any(Long.class)))
                .thenAnswer(invocation -> java.net.URI.create("https://media.example.test/signed"));

        db.sql("DELETE FROM video_shot_take").then()
                .then(db.sql("DELETE FROM video_shot_audio").then())
                .then(db.sql("DELETE FROM video_production_task").then())
                .then(db.sql("DELETE FROM video_shot").then())
                .then(db.sql("DELETE FROM video_storyboard").then())
                .then(db.sql("DELETE FROM ai_run").then())
                .then(db.sql("DELETE FROM platform_model_config WHERE capability='video_generation'").then())
                // 按（provider, base_url）目的地清理：共享容器里其他 IT 会在同 baseUrl 建凭据
                .then(db.sql("DELETE FROM platform_provider_credential WHERE base_url=:baseUrl "
                        + "AND provider IN ('seedance','minimax','wan','sandbox')").bind("baseUrl", QWEN.baseUrl()).then())
                .block(Duration.ofSeconds(10));
        QWEN.resetAll();
        seedVideoModel("sandbox", "sandbox-video-v1", null);
    }

    @Test
    @DisplayName("sandbox 全链：2 镜 × 2 take 全部成功、进度/推荐/详情可见、锚定图任务可建")
    void sandboxTaskFullChain() {
        UUID storyboardId = seedStoryboard(30, "[\"" + IMAGE_1 + "\",\"" + IMAGE_2 + "\"]");
        UUID shot1 = seedShot(storyboardId, 1, 5, 1);
        UUID shot2 = seedShot(storyboardId, 2, 5, 2);

        VideoProductionTask task = taskService
                .create(ACCOUNT, null, new VideoProductionTaskService.CreateRequest(storyboardId, "op-1"))
                .block(Duration.ofSeconds(20));
        assertThat(task.mode()).isEqualTo("video");
        assertThat(task.provider()).isEqualTo("sandbox");
        // 预估 = 30 秒 × 1 分/秒（sandbox-video-v1 兜底价）
        assertThat(task.estimatedCostCents()).isEqualTo(30);
        assertThat(task.unitPriceCents()).isEqualTo(1);
        // create() 返回建行快照；run/phase 断言回读库内最新行
        task = tasks.findById(task.id(), ACCOUNT).block(Duration.ofSeconds(5));
        assertThat(task.runId()).isNotNull();
        assertThat(task.phase()).isEqualTo(VideoProductionTask.PHASE_GENERATING);

        // 幂等：同 operationId 复读不重复建
        VideoProductionTask replay = taskService
                .create(ACCOUNT, null, new VideoProductionTaskService.CreateRequest(storyboardId, "op-1"))
                .block(Duration.ofSeconds(20));
        assertThat(replay.id()).isEqualTo(task.id());
        Long taskCount = db.sql("SELECT COUNT(*) AS n FROM video_production_task")
                .map(row -> row.get("n", Long.class)).one().block();
        assertThat(taskCount).isEqualTo(1L);
        // 4 take + 2 audio 行
        assertThat(takes.findByStoryboard(storyboardId).collectList().block().size()).isEqualTo(4);
        assertThat(audios.findByStoryboard(storyboardId).collectList().block().size()).isEqualTo(2);

        // 手动驱动全部候选（worker 调度在 IT 关闭）
        driveAllTakes(storyboardId);
        List<VideoShotTake> all = takes.findByStoryboard(storyboardId).collectList().block();
        assertThat(all).allSatisfy(take -> {
            assertThat(take.status()).isEqualTo(VideoShotTake.STATUS_SUCCEEDED);
            assertThat(take.mediaId()).isNotNull();
        });
        assertThat(storedKeys).hasSize(4);

        // 详情：推荐 + presign + 进度
        client().get().uri("/api/video-production/tasks/{id}", task.id())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.phase").isEqualTo("generating")
                .jsonPath("$.data.shots.length()").isEqualTo(2)
                .jsonPath("$.data.shots[0].takes.length()").isEqualTo(2)
                .jsonPath("$.data.shots[0].takes[0].selectable").isEqualTo(true)
                .jsonPath("$.data.shots[0].takes[0].url").isEqualTo("https://media.example.test/signed")
                .jsonPath("$.data.recommended." + shot1).isNotEmpty()
                .jsonPath("$.data.recommended." + shot2).isNotEmpty();
    }

    @Test
    @DisplayName("锚定图映射：第 2 张图作为首镜 first_frame 发给 provider（WireMock seedance）")
    void anchorImageMappingSecondImageAsFirstFrame() {
        seedVideoModel("seedance", "qwen-plus", "VG-SEED");
        QWEN.stubFor(post(urlEqualTo("/api/v3/contents/generations/tasks"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"vid-task-1\"}")));
        QWEN.stubFor(get(urlEqualTo("/api/v3/contents/generations/tasks/vid-task-1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"succeeded\",\"content\":{\"video_url\":\""
                                + QWEN.baseUrl() + "/files/clip.mp4\"}}")));
        QWEN.stubFor(get(urlEqualTo("/files/clip.mp4"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "video/mp4")
                        .withBody(new byte[] { 0, 0, 0, 8, 'f', 't', 'y', 'p', 1, 2 })));

        UUID storyboardId = seedStoryboard(20, "[\"" + IMAGE_1 + "\",\"" + IMAGE_2 + "\"]");
        seedShot(storyboardId, 1, 5, 2); // 首镜锚定第 2 张
        VideoProductionTask task = taskService
                .create(ACCOUNT, null, new VideoProductionTaskService.CreateRequest(storyboardId, null))
                .block(Duration.ofSeconds(20));
        driveAllTakes(storyboardId);

        QWEN.verify(postRequestedFor(urlEqualTo("/api/v3/contents/generations/tasks"))
                .withRequestBody(containing("BBBB"))
                .withRequestBody(containing("first_frame")));
        // BBDDD… not：第 1 张图不得出现在首镜载荷
        QWEN.verify(0, postRequestedFor(urlEqualTo("/api/v3/contents/generations/tasks"))
                .withRequestBody(containing("AAAA")));
        List<VideoShotTake> all = takes.findByStoryboard(storyboardId).collectList().block();
        assertThat(all).allSatisfy(take -> assertThat(take.status()).isEqualTo(VideoShotTake.STATUS_SUCCEEDED));
    }

    @Test
    @DisplayName("失败重试到限不阻塞他镜，全部终态且无可选候选 → 任务失败并退款")
    void failingProviderRetriesToLimitThenTaskFailsWithRefund() {
        seedVideoModel("seedance", "qwen-plus", "VG-FAIL");
        QWEN.stubFor(post(urlEqualTo("/api/v3/contents/generations/tasks"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        UUID storyboardId = seedStoryboard(15, "[\"" + IMAGE_1 + "\"]");
        seedShot(storyboardId, 1, 5, 1);
        seedShot(storyboardId, 2, 5, 1);
        VideoProductionTask task = taskService
                .create(ACCOUNT, null, new VideoProductionTaskService.CreateRequest(storyboardId, null))
                .block(Duration.ofSeconds(20));

        // max-attempts=2：领单-处理两轮后到限（每轮复位 lease 与退避）
        for (int cycle = 0; cycle < 4; cycle++) {
            takes.claimBatch(20, Duration.ofSeconds(30)).flatMap(takeWorker::process)
                    .then().block(Duration.ofSeconds(30));
            db.sql("UPDATE video_shot_take SET claimed_until=NULL, next_attempt_at=now() "
                    + "WHERE status IN ('queued','submitted','processing')").then().block(Duration.ofSeconds(5));
        }
        takes.claimBatch(20, Duration.ofSeconds(30)).flatMap(takeWorker::process)
                .then().block(Duration.ofSeconds(30));

        VideoProductionTask failed = tasks.findById(task.id(), ACCOUNT).block(Duration.ofSeconds(5));
        assertThat(failed.phase()).isEqualTo(VideoProductionTask.PHASE_FAILED);
        assertThat(failed.errorCode()).isEqualTo("take_all_failed");
        // 退款：ai_run failed（预留释放走 handleFailure）
        String run = db.sql("SELECT status FROM ai_run WHERE id=CAST(:run AS uuid)")
                .bind("run", failed.runId().toString())
                .map(row -> row.get("status", String.class)).one().block();
        assertThat(run).isEqualTo("failed");
    }

    @Test
    @DisplayName("选片/重抽/取消/历史：useRecommended 全选、重抽续 take_no、取消退款零悬空")
    void selectRegenerateCancelHistory() {
        UUID storyboardId = seedStoryboard(15, "[\"" + IMAGE_1 + "\"]");
        UUID shot1 = seedShot(storyboardId, 1, 5, 1);
        VideoProductionTask task = taskService
                .create(ACCOUNT, null, new VideoProductionTaskService.CreateRequest(storyboardId, null))
                .block(Duration.ofSeconds(20));
        driveAllTakes(storyboardId);
        UUID takeId = takes.findByShot(shot1).collectList().block().getFirst().id();

        // useRecommended
        client().post().uri("/api/video-production/tasks/{id}/takes/select", task.id())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("useRecommended", true))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.selection." + shot1).isEqualTo(takeId.toString());

        // 重抽：take_no 续排 3、4
        client().post().uri("/api/video-production/tasks/{id}/shots/{shotId}/regenerate", task.id(), shot1)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.createdTakes").isEqualTo(2);
        List<VideoShotTake> afterRegen = takes.findByShot(shot1).collectList().block();
        assertThat(afterRegen).hasSize(4);

        // 历史分页
        client().get().uri("/api/video-production/tasks?page=1&pageSize=10")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.total").isEqualTo(1)
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].mode").isEqualTo("video");

        // 取消：全额退（ai_run failed）+ 候选收口
        client().post().uri("/api/video-production/tasks/{id}/cancel", task.id())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.cancelled").isEqualTo(true);
        VideoProductionTask cancelled = tasks.findById(task.id(), ACCOUNT).block(Duration.ofSeconds(5));
        assertThat(cancelled.phase()).isEqualTo(VideoProductionTask.PHASE_CANCELLED);
        String run = db.sql("SELECT status FROM ai_run WHERE id=CAST(:run AS uuid)")
                .bind("run", cancelled.runId().toString())
                .map(row -> row.get("status", String.class)).one().block();
        assertThat(run).isEqualTo("failed");
    }

    @Test
    @DisplayName("积分不足 → 402 且任务行不留无 run 僵尸")
    void insufficientCreditsFailsClosed() {
        when(credits.consume(anyString(), any(CreditFeature.class), anyString()))
                .thenReturn(Mono.error(new InsufficientCreditsException()));
        UUID storyboardId = seedStoryboard(15, "[\"" + IMAGE_1 + "\"]");
        seedShot(storyboardId, 1, 5, 1);

        client().post().uri("/api/video-production/tasks")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("storyboardId", storyboardId.toString()))
                .exchange().expectStatus().isEqualTo(402);

        String zombie = db.sql("SELECT phase FROM video_production_task "
                        + "WHERE storyboard_id=CAST(:sb AS uuid)")
                .bind("sb", storyboardId.toString())
                .map(row -> row.get("phase", String.class)).one().block();
        assertThat(zombie).isEqualTo("failed");
    }

    // ---------------- helpers ----------------

    /** 手动驱动：每轮先释放 lease/退避（claim 语义由调度器持有，测试代为复位）。 */
    private void driveAllTakes(UUID storyboardId) {
        for (int round = 0; round < 4; round++) {
            takes.claimBatch(20, Duration.ofSeconds(30))
                    .flatMap(takeWorker::process)
                    .then().block(Duration.ofSeconds(60));
            db.sql("UPDATE video_shot_take SET claimed_until=NULL, next_attempt_at=now() "
                    + "WHERE status IN ('queued','submitted','processing')").then().block(Duration.ofSeconds(5));
        }
    }

    private void seedVideoModel(String provider, String model, String credentialTag) {
        String name = "it-video-vg-" + (credentialTag == null ? provider : credentialTag);
        String encrypted = encryptionProvider.getIfAvailable().encrypt("sk-it-vg-key");
        String encryptedBound = "sandbox".equals(provider) ? "" : encrypted;
        // (capability, model_role) 部分唯一：换 provider 种子前先清既有行与凭据（含插槽）
        db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN "
                        + "(SELECT id FROM platform_model_config WHERE capability='video_generation')").then()
                .then(db.sql("DELETE FROM platform_model_config WHERE capability='video_generation'").then())
                .then(db.sql("DELETE FROM platform_provider_credential WHERE base_url=:baseUrl "
                        + "AND provider IN ('seedance','minimax','wan','sandbox')")
                        .bind("baseUrl", QWEN.baseUrl()).then())
                .block(Duration.ofSeconds(10));
        db.sql("""
                WITH cred AS (
                    INSERT INTO platform_provider_credential(name, provider, base_url,
                        encrypted_key, key_version, masked_hint, enabled)
                    VALUES (:name, :provider, :baseUrl, CAST(NULLIF(:encrypted,'') AS text),
                        'v1', 'sk-***vg', true)
                    RETURNING id, base_url
                )
                INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
                    health_status, enabled, version, credential_id)
                SELECT 'video_generation', 'primary', :provider, :model, cred.base_url, 'healthy', true, 1, cred.id
                FROM cred
                """)
                .bind("name", name)
                .bind("provider", provider)
                .bind("baseUrl", QWEN.baseUrl())
                .bind("encrypted", encryptedBound)
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

    private UUID seedShot(UUID storyboardId, int seq, int plannedSeconds, int anchorImageIndex) {
        return UUID.fromString(db.sql("""
                        INSERT INTO video_shot(storyboard_id, seq, visual, narration, planned_seconds,
                            camera_move, anchor_image_index, prompt)
                        VALUES (CAST(:sb AS uuid), :seq, '画面', '旁白', :planned, '固定机位', :anchor, '提示词')
                        RETURNING id::text
                        """)
                .bind("sb", storyboardId.toString()).bind("seq", seq).bind("planned", plannedSeconds)
                .bind("anchor", anchorImageIndex)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }
}
