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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * 任务书 #100 C100-01（API-01 / V71）：选片原子合并与单调版本回归。
 *
 * <p>覆盖 TC-001～004：连续非推荐选择持久（刷新不被推荐覆盖）、并发普通/推荐按行锁生效、
 * 同镜快速点击最终意图落库、非法状态（匿名/越权/composing/终态/不可选/伪造归属/超限）零写入。
 */
@DisplayName("Video canvas selection (C100-01)")
@TestPropertySource(properties = { "ai.video-generation.worker-enabled=false",
        "ai.video-generation.max-attempts=2" })
class VideoCanvasSelectionIT extends IntelligenceItSupport {

    private static final String ACCOUNT = "62626262-6262-6262-6262-626262626262";
    private static final String ACCOUNT_B = "63636363-6363-6363-6363-636363636363";
    private static final String IMAGE_1 = "data:image/png;base64,AAAA";

    @MockitoBean
    CreditsClient credits;

    @MockitoBean
    ObjectStorageAdapter storage;

    @Autowired
    VideoProductionTaskService taskService;

    @Autowired
    VideoShotTakeRepository takes;

    @Autowired
    VideoProductionTaskRepository tasks;

    @BeforeEach
    void cleanAndSeed() {
        reset(credits, storage);
        when(credits.consume(anyString(), any(CreditFeature.class), anyString()))
                .thenAnswer(invocation -> Mono.just(new CreditCharge(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2))));
        when(credits.refund(any(), anyString())).thenReturn(Mono.empty());
        when(storage.presignDownload(anyString(), any(Long.class)))
                .thenAnswer(invocation -> java.net.URI.create("https://media.example.test/signed"));

        db.sql("DELETE FROM video_shot_take").then()
                .then(db.sql("DELETE FROM video_shot_audio").then())
                .then(db.sql("DELETE FROM video_production_task").then())
                .then(db.sql("DELETE FROM video_shot").then())
                .then(db.sql("DELETE FROM video_storyboard").then())
                .then(db.sql("DELETE FROM ai_run").then())
                .then(db.sql("DELETE FROM platform_model_config WHERE capability='video_generation'").then())
                .then(db.sql("DELETE FROM platform_provider_credential WHERE base_url=:baseUrl "
                        + "AND provider IN ('seedance','minimax','wan','sandbox')").bind("baseUrl", QWEN.baseUrl()).then())
                .block(Duration.ofSeconds(10));
        QWEN.resetAll();
        seedVideoModel("sandbox", "sandbox-video-v1", null);
    }

    /** 两镜候选夹具：任务已建、候选已成功可选用（SQL 直推成功态，绕过 worker 调度）。 */
    private record SeededTask(VideoProductionTask task, UUID shot1, UUID shot2, UUID s1Take1, UUID s1Take2,
            UUID s2Take1, UUID s2Take2) {
    }

    private SeededTask seedTaskWithTwoSelectableTakesPerShot(String operationId) {
        UUID storyboardId = seedStoryboard(25);
        UUID shot1 = seedShot(storyboardId, 1);
        UUID shot2 = seedShot(storyboardId, 2);
        VideoProductionTask task = taskService
                .create(ACCOUNT, null, new VideoProductionTaskService.CreateRequest(storyboardId, operationId))
                .block(Duration.ofSeconds(20));
        succeedAllTakes(storyboardId);
        List<VideoShotTake> shot1Takes = takes.findByShot(shot1).collectList().block(Duration.ofSeconds(5));
        List<VideoShotTake> shot2Takes = takes.findByShot(shot2).collectList().block(Duration.ofSeconds(5));
        assertThat(shot1Takes).hasSize(2);
        assertThat(shot2Takes).hasSize(2);
        return new SeededTask(task, shot1, shot2,
                shot1Takes.get(0).id(), shot1Takes.get(1).id(),
                shot2Takes.get(0).id(), shot2Takes.get(1).id());
    }

    @Test
    @DisplayName("TC-001：两镜连续采用非推荐候选，跨会话持久且刷新不被推荐覆盖")
    void sequentialNonRecommendedSelectionsPersistAcrossSessions() {
        SeededTask seeded = seedTaskWithTwoSelectableTakesPerShot("op-tc1");

        // 客户端 A：采用 S1 的第 2 候选（非推荐——推荐 = 每镜首个成功）
        client().post().uri("/api/video-production/tasks/{id}/takes/select", seeded.task().id())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("selections",
                        List.of(Map.of("shotId", seeded.shot1().toString(), "takeId", seeded.s1Take2().toString()))))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.selection." + seeded.shot1()).isEqualTo(seeded.s1Take2().toString())
                .jsonPath("$.data.selectionVersion").isEqualTo(1);

        // 「销毁会话」后新客户端只带本镜局部选择（无任何本地累积状态）
        client().post().uri("/api/video-production/tasks/{id}/takes/select", seeded.task().id())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("selections",
                        List.of(Map.of("shotId", seeded.shot2().toString(), "takeId", seeded.s2Take2().toString()))))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.selection." + seeded.shot2()).isEqualTo(seeded.s2Take2().toString())
                .jsonPath("$.data.selectionVersion").isEqualTo(2);

        // 数据库直查（不是只看页面）：两项非推荐选择共存，版本单调
        assertSelectionRow(seeded.task().id(), seeded.s1Take2(), seeded.s2Take2());
        assertSelectionVersion(seeded.task().id(), 2L);

        // 新会话 GET 详情：完整选择 + 版本；推荐不再回灌覆盖显式选择
        client().get().uri("/api/video-production/tasks/{id}", seeded.task().id())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.selection." + seeded.shot1()).isEqualTo(seeded.s1Take2().toString())
                .jsonPath("$.data.selection." + seeded.shot2()).isEqualTo(seeded.s2Take2().toString())
                .jsonPath("$.data.selectionVersion").isEqualTo(2);

        // 无额外生成/扣费：候选行数不变（2 镜 × 2 take），单任务单 run，预估不动
        assertThat(takes.findByStoryboard(seeded.task().storyboardId()).collectList().block(Duration.ofSeconds(5)))
                .hasSize(4);
        VideoProductionTask reloaded = tasks.findById(seeded.task().id(), ACCOUNT).block(Duration.ofSeconds(5));
        assertThat(reloaded.estimatedCostCents()).isEqualTo(seeded.task().estimatedCostCents());
        Long runRows = db.sql("SELECT COUNT(*) AS n FROM ai_run").map(row -> row.get("n", Long.class))
                .one().block(Duration.ofSeconds(5));
        assertThat(runRows).isEqualTo(1L);
    }

    @Test
    @DisplayName("TC-002：并发普通选择无丢失；推荐替换与单镜选择按行锁生效、版本单调")
    void concurrentSelectsMergeWithoutLossAndVersionMonotonic() {
        SeededTask seeded = seedTaskWithTwoSelectableTakesPerShot("op-tc2");

        // 两客户端并发编辑不同镜头：普通合并语义下两个局部选择都必须存活
        Mono<VideoProductionTaskService.SelectionOutcome> first = taskService.select(seeded.task().id(), ACCOUNT,
                List.of(new VideoProductionTaskService.Selection(seeded.shot1(), seeded.s1Take2())), false);
        Mono<VideoProductionTaskService.SelectionOutcome> second = taskService.select(seeded.task().id(), ACCOUNT,
                List.of(new VideoProductionTaskService.Selection(seeded.shot2(), seeded.s2Take2())), false);
        var both = Mono.zip(first, second).block(Duration.ofSeconds(10));

        long maxVersion = Math.max(both.getT1().selectionVersion(), both.getT2().selectionVersion());
        long minVersion = Math.min(both.getT1().selectionVersion(), both.getT2().selectionVersion());
        assertThat(minVersion).isEqualTo(1L);
        assertThat(maxVersion).isEqualTo(2L);
        assertSelectionRow(seeded.task().id(), seeded.s1Take2(), seeded.s2Take2());
        assertSelectionVersion(seeded.task().id(), 2L);

        // 推荐全量替换与单镜局部选择并发：按行锁提交顺序生效，终态完整且版本只升不降
        Mono<VideoProductionTaskService.SelectionOutcome> recommended = taskService.select(seeded.task().id(),
                ACCOUNT, List.of(), true);
        Mono<VideoProductionTaskService.SelectionOutcome> single = taskService.select(seeded.task().id(), ACCOUNT,
                List.of(new VideoProductionTaskService.Selection(seeded.shot1(), seeded.s1Take1())), false);
        var race = Mono.zip(recommended, single).block(Duration.ofSeconds(10));

        // 提交顺序不定，但两次写入各占一个版本位且终态版本为 4
        long raceMin = Math.min(race.getT1().selectionVersion(), race.getT2().selectionVersion());
        long raceMax = Math.max(race.getT1().selectionVersion(), race.getT2().selectionVersion());
        assertThat(raceMin).isEqualTo(3L);
        assertThat(raceMax).isEqualTo(4L);
        assertSelectionVersion(seeded.task().id(), 4L);
        // 终态完整性：每镜都有选择，且值属于该镜的可选候选
        var finalRow = selectionRow(seeded.task().id());
        assertThat(finalRow.get(seeded.shot1().toString())).isIn(seeded.s1Take1().toString(),
                seeded.s1Take2().toString());
        assertThat(finalRow.get(seeded.shot2().toString())).isIn(seeded.s2Take1().toString(),
                seeded.s2Take2().toString());
    }

    @Test
    @DisplayName("TC-003：同镜快速选择 T11→T12→T11，最终按最后意图落库且响应完整")
    void sameShotRapidSelectsFinalIntentWins() {
        SeededTask seeded = seedTaskWithTwoSelectableTakesPerShot("op-tc3");

        VideoProductionTaskService.SelectionOutcome first = taskService
                .select(seeded.task().id(), ACCOUNT,
                        List.of(new VideoProductionTaskService.Selection(seeded.shot1(), seeded.s1Take1())), false)
                .block(Duration.ofSeconds(10));
        VideoProductionTaskService.SelectionOutcome second = taskService
                .select(seeded.task().id(), ACCOUNT,
                        List.of(new VideoProductionTaskService.Selection(seeded.shot1(), seeded.s1Take2())), false)
                .block(Duration.ofSeconds(10));
        VideoProductionTaskService.SelectionOutcome third = taskService
                .select(seeded.task().id(), ACCOUNT,
                        List.of(new VideoProductionTaskService.Selection(seeded.shot1(), seeded.s1Take1())), false)
                .block(Duration.ofSeconds(10));

        // 每次响应都带完整选择与单调版本
        assertThat(first.selectionVersion()).isEqualTo(1L);
        assertThat(first.selection()).containsOnlyKeys(seeded.shot1().toString());
        assertThat(second.selectionVersion()).isEqualTo(2L);
        assertThat(second.selection().get(seeded.shot1().toString())).isEqualTo(seeded.s1Take2());
        assertThat(third.selectionVersion()).isEqualTo(3L);
        assertThat(third.selection().get(seeded.shot1().toString())).isEqualTo(seeded.s1Take1());

        assertSelectionVersion(seeded.task().id(), 3L);
        var finalRow = selectionRow(seeded.task().id());
        assertThat(finalRow).containsOnlyKeys(seeded.shot1().toString());
        assertThat(finalRow.get(seeded.shot1().toString())).isEqualTo(seeded.s1Take1().toString());
    }

    @Test
    @DisplayName("TC-004：匿名/越权/composing/终态/不可选/伪造归属/重复镜头/超限/useRecommended 混用均零写入")
    void selectRejectsInvalidStatesWithoutSideEffects() {
        SeededTask seeded = seedTaskWithTwoSelectableTakesPerShot("op-tc4");
        UUID taskId = seeded.task().id();

        // 匿名 401
        client().post().uri("/api/video-production/tasks/{id}/takes/select", taskId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("useRecommended", true))
                .exchange().expectStatus().isUnauthorized();

        // 跨账号 404（不泄露对象存在性）
        client().post().uri("/api/video-production/tasks/{id}/takes/select", taskId)
                .header("X-Grassland-Identity", sign(ACCOUNT_B, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("useRecommended", true))
                .exchange().expectStatus().isNotFound();

        // useRecommended 与 selections 混用 → 400
        client().post().uri("/api/video-production/tasks/{id}/takes/select", taskId)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("useRecommended", true, "selections",
                        List.of(Map.of("shotId", seeded.shot1().toString(), "takeId", seeded.s1Take1().toString()))))
                .exchange().expectStatus().isBadRequest();

        // 重复镜头 → 400
        client().post().uri("/api/video-production/tasks/{id}/takes/select", taskId)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("selections", List.of(
                        Map.of("shotId", seeded.shot1().toString(), "takeId", seeded.s1Take1().toString()),
                        Map.of("shotId", seeded.shot1().toString(), "takeId", seeded.s1Take2().toString()))))
                .exchange().expectStatus().isBadRequest();

        // 超 30 项 → 400（先做数量闸，不逐项查归属）
        List<Map<String, String>> oversized = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            oversized.add(Map.of("shotId", UUID.randomUUID().toString(), "takeId", UUID.randomUUID().toString()));
        }
        client().post().uri("/api/video-production/tasks/{id}/takes/select", taskId)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("selections", oversized))
                .exchange().expectStatus().isBadRequest();

        // 伪造归属：shot1 配 shot2 的候选 → 404
        client().post().uri("/api/video-production/tasks/{id}/takes/select", taskId)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("selections",
                        List.of(Map.of("shotId", seeded.shot1().toString(), "takeId", seeded.s2Take1().toString()))))
                .exchange().expectStatus().isNotFound();

        // 不可选候选（failed 态）→ 409
        db.sql("UPDATE video_shot_take SET status='failed' WHERE id=CAST(:id AS uuid)")
                .bind("id", seeded.s2Take2().toString()).then().block(Duration.ofSeconds(5));
        client().post().uri("/api/video-production/tasks/{id}/takes/select", taskId)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("selections",
                        List.of(Map.of("shotId", seeded.shot2().toString(), "takeId", seeded.s2Take2().toString()))))
                .exchange().expectStatus().isEqualTo(409);

        // 上述拒绝零写入
        assertSelectionEmpty(taskId);

        // composing 阶段 → 409 且不落任何变更
        db.sql("UPDATE video_production_task SET phase='composing' WHERE id=CAST(:id AS uuid)")
                .bind("id", taskId.toString()).then().block(Duration.ofSeconds(5));
        client().post().uri("/api/video-production/tasks/{id}/takes/select", taskId)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("useRecommended", true))
                .exchange().expectStatus().isEqualTo(409);
        assertSelectionEmpty(taskId);

        // 终态（cancelled）→ 409
        db.sql("UPDATE video_production_task SET phase='cancelled' WHERE id=CAST(:id AS uuid)")
                .bind("id", taskId.toString()).then().block(Duration.ofSeconds(5));
        client().post().uri("/api/video-production/tasks/{id}/takes/select", taskId)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("useRecommended", true))
                .exchange().expectStatus().isEqualTo(409);
        assertSelectionEmpty(taskId);
    }

    // ---------------- helpers ----------------

    private void succeedAllTakes(UUID storyboardId) {
        db.sql("UPDATE video_shot_take t SET status='succeeded', media_id=gen_random_uuid(), "
                        + "completed_at=now(), updated_at=now() "
                        + "FROM video_shot s WHERE t.shot_id=s.id AND s.storyboard_id=CAST(:sb AS uuid)")
                .bind("sb", storyboardId.toString()).then().block(Duration.ofSeconds(10));
    }

    /** 候选成功后按（镜序, take_no）读取库内 selection JSON 为 Map。 */
    private Map<String, String> selectionRow(UUID taskId) {
        String json = db.sql("SELECT selection::text AS s FROM video_production_task WHERE id=CAST(:id AS uuid)")
                .bind("id", taskId.toString())
                .map(row -> row.get("s", String.class)).one().block(Duration.ofSeconds(5));
        assertThat(json).isNotBlank();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {
                    });
        } catch (Exception error) {
            throw new IllegalStateException("selection 解析失败: " + json, error);
        }
    }

    private void assertSelectionRow(UUID taskId, UUID expectedShot1Take, UUID expectedShot2Take) {
        Map<String, String> row = selectionRow(taskId);
        assertThat(row.get(storyboardShotKey(taskId, expectedShot1Take)))
                .isEqualTo(expectedShot1Take.toString());
        assertThat(row.get(storyboardShotKey(taskId, expectedShot2Take)))
                .isEqualTo(expectedShot2Take.toString());
    }

    /** 从候选反查 shotId 键（测试夹具里 selection 的键是 shot uuid 文本）。 */
    private String storyboardShotKey(UUID taskId, UUID takeId) {
        return db.sql("SELECT s.id::text AS k FROM video_shot s JOIN video_shot_take t ON t.shot_id=s.id "
                        + "JOIN video_production_task v ON v.storyboard_id=s.storyboard_id "
                        + "WHERE v.id=CAST(:task AS uuid) AND t.id=CAST(:take AS uuid)")
                .bind("task", taskId.toString())
                .bind("take", takeId.toString())
                .map(row -> row.get("k", String.class)).one().block(Duration.ofSeconds(5));
    }

    private void assertSelectionVersion(UUID taskId, long expected) {
        Long version = db.sql("SELECT selection_version AS v FROM video_production_task "
                        + "WHERE id=CAST(:id AS uuid)")
                .bind("id", taskId.toString())
                .map(row -> row.get("v", Long.class)).one().block(Duration.ofSeconds(5));
        assertThat(version).isEqualTo(expected);
    }

    private void assertSelectionEmpty(UUID taskId) {
        String selection = db.sql("SELECT COALESCE(selection::text,'') AS s FROM video_production_task "
                        + "WHERE id=CAST(:id AS uuid)")
                .bind("id", taskId.toString())
                .map(row -> row.get("s", String.class)).one().block(Duration.ofSeconds(5));
        Long version = db.sql("SELECT selection_version AS v FROM video_production_task "
                        + "WHERE id=CAST(:id AS uuid)")
                .bind("id", taskId.toString())
                .map(row -> row.get("v", Long.class)).one().block(Duration.ofSeconds(5));
        assertThat(selection).isEmpty();
        assertThat(version).isEqualTo(0L);
    }

    private void seedVideoModel(String provider, String model, String credentialTag) {
        String name = "it-video-sel-" + (credentialTag == null ? provider : credentialTag);
        String encrypted = encryptionProvider.getIfAvailable().encrypt("sk-it-sel-key");
        String encryptedBound = "sandbox".equals(provider) ? "" : encrypted;
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
                        'v1', 'sk-***sel', true)
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

    private UUID seedStoryboard(int targetDurationSeconds) {
        String payload = "{\"images\":[\"" + IMAGE_1 + "\"],\"shopName\":\"店\"}";
        return UUID.fromString(db.sql("INSERT INTO video_storyboard(account_id, target_duration_seconds, "
                        + "request_payload) VALUES (:account, :duration, CAST(:payload AS jsonb)) RETURNING id::text")
                .bind("account", ACCOUNT).bind("duration", targetDurationSeconds)
                .bind("payload", payload)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }

    private UUID seedShot(UUID storyboardId, int seq) {
        return UUID.fromString(db.sql("""
                        INSERT INTO video_shot(storyboard_id, seq, visual, narration, planned_seconds,
                            camera_move, anchor_image_index, prompt)
                        VALUES (CAST(:sb AS uuid), :seq, '画面', '旁白', 5, '固定机位', 1, '提示词')
                        RETURNING id::text
                        """)
                .bind("sb", storyboardId.toString()).bind("seq", seq)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }
}
