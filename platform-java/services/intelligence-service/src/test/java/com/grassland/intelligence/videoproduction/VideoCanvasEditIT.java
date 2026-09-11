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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

/**
 * 任务书 #100 C100-03（API-02～04/06、§7.2）：分镜编辑版本与可靠保稿的服务端回归。
 *
 * <p>覆盖 TC-007～009 服务端部分：无实际变化不提升版本、visual→prompt 同步与旁白不覆盖、
 * 并发 CAS 一成一冲突、旧客户端兼容升版本、committed 拒绝、批量编辑全成或全不写、
 * 晚到锚定图按启动版本 CAS 落锚、建任务冻结与编辑互斥。
 */
@DisplayName("Video canvas storyboard editing (C100-03)")
@TestPropertySource(properties = { "ai.video-generation.worker-enabled=false",
        "ai.video-generation.max-attempts=2" })
class VideoCanvasEditIT extends IntelligenceItSupport {

    private static final String ACCOUNT = "64646464-6464-6464-6464-646464646464";
    private static final String OTHER = "65656565-6565-6565-6565-656565656565";

    @MockitoBean
    CreditsClient credits;

    @MockitoBean
    ObjectStorageAdapter storage;

    @Autowired
    VideoStoryboardEditService editService;

    @Autowired
    VideoProductionTaskService taskService;

    @Autowired
    VideoShotRepository shots;

    @Autowired
    VideoShotTakeRepository takes;

    @Autowired
    VideoStoryboardRepository storyboards;

    @BeforeEach
    void cleanAndSeed() {
        reset(credits, storage);
        when(credits.consume(anyString(), any(CreditFeature.class), anyString()))
                .thenAnswer(invocation -> Mono.just(new CreditCharge(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2))));
        when(credits.refund(any(), anyString())).thenReturn(Mono.empty());
        db.sql("DELETE FROM video_shot_take").then()
                .then(db.sql("DELETE FROM video_shot_audio").then())
                .then(db.sql("DELETE FROM video_production_task").then())
                .then(db.sql("DELETE FROM video_shot").then())
                .then(db.sql("DELETE FROM video_storyboard").then())
                .then(db.sql("DELETE FROM ai_run").then())
                .then(db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN "
                        + "(SELECT id FROM platform_model_config WHERE capability='video_generation')").then())
                .then(db.sql("DELETE FROM platform_model_config WHERE capability='video_generation'").then())
                .then(db.sql("DELETE FROM platform_provider_credential WHERE provider='sandbox' "
                        + "AND base_url='https://sandbox.invalid'").then())
                .block(Duration.ofSeconds(10));
    }

    private record Seeded(UUID storyboardId, UUID shot1, UUID shot2, UUID shot3) {
    }

    private Seeded seedStoryboard(int shotCount, String imagesJson) {
        UUID storyboardId = UUID.fromString(db.sql("""
                        INSERT INTO video_storyboard(account_id, target_duration_seconds, request_payload)
                        VALUES (:account, 25, CAST(:payload AS jsonb))
                        RETURNING id::text
                        """)
                .bind("account", ACCOUNT)
                .bind("payload", "{\"images\":" + imagesJson + ",\"shopName\":\"店\"}")
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
        UUID shot1 = seedShot(storyboardId, 1);
        UUID shot2 = shotCount > 1 ? seedShot(storyboardId, 2) : null;
        UUID shot3 = shotCount > 2 ? seedShot(storyboardId, 3) : null;
        return new Seeded(storyboardId, shot1, shot2, shot3);
    }

    private UUID seedShot(UUID storyboardId, int seq) {
        return UUID.fromString(db.sql("""
                        INSERT INTO video_shot(storyboard_id, seq, visual, narration, planned_seconds,
                            camera_move, anchor_image_index, prompt, status)
                        VALUES (CAST(:sb AS uuid), :seq, '画面', '旁白', 5, '固定机位', 0, '旧提示词', 'ready')
                        RETURNING id::text
                        """)
                .bind("sb", storyboardId.toString()).bind("seq", seq)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }

    private long editVersion(UUID storyboardId) {
        Long version = db.sql("SELECT edit_version AS v FROM video_storyboard WHERE id=CAST(:id AS uuid)")
                .bind("id", storyboardId.toString())
                .map(row -> row.get("v", Long.class)).one().block(Duration.ofSeconds(5));
        return version == null ? 1L : version;
    }

    @Test
    @DisplayName("TC-007 服务端：与原值相同的内容保存零写入零版本提升；详情返回 editVersion")
    void sameContentDoesNotBumpVersion() {
        Seeded seeded = seedStoryboard(2, "[]");
        assertThat(editVersion(seeded.storyboardId())).isEqualTo(1L);

        // GET 详情带 editVersion（API-02）
        client().get().uri("/api/video-production/storyboards/{id}", seeded.storyboardId())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.editVersion").isEqualTo(1);

        // 与行上原值完全相同的 PUT：无实际变化不提升
        client().put().uri("/api/video-production/shots/{id}/content", seeded.shot1())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("visual", "画面", "narration", "旁白", "plannedSeconds", 5,
                        "cameraMove", "固定机位", "anchorImageIndex", 0, "expectedEditVersion", 1))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.editVersion").isEqualTo(1)
                .jsonPath("$.data.updatedShotIds.length()").isEqualTo(0);
        assertThat(editVersion(seeded.storyboardId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("TC-009 服务端：改 visual 同步 prompt；只改旁白不覆盖 prompt；版本单调")
    void visualEditSyncsPromptNarrationDoesNot() {
        Seeded seeded = seedStoryboard(2, "[]");

        // 只改旁白：prompt 不动，版本 +1
        client().put().uri("/api/video-production/shots/{id}/content", seeded.shot1())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("narration", "新旁白", "expectedEditVersion", 1))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.editVersion").isEqualTo(2);
        assertThat(shotPrompt(seeded.shot1())).isEqualTo("旧提示词");

        // 显式改 visual：prompt 同步为新 visual（§6.2 生成正确性行为），版本再 +1
        client().put().uri("/api/video-production/shots/{id}/content", seeded.shot1())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("visual", "新画面", "expectedEditVersion", 2))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.editVersion").isEqualTo(3);
        assertThat(shotPrompt(seeded.shot1())).isEqualTo("新画面");
        assertThat(editVersion(seeded.storyboardId())).isEqualTo(3L);
    }

    @Test
    @DisplayName("TC-009 服务端：两客户端同版本并发保存一成一 409；旧客户端不带版本兼容但升版本")
    void concurrentCasOneWinsOldClientCompatible() {
        Seeded seeded = seedStoryboard(2, "[]");

        // 并发：两个带版本（都基于 v1）的保存——按行锁一成一冲突
        Mono<VideoStoryboardEditService.ShotContentResult> first = editService.updateShotContent(ACCOUNT,
                seeded.shot1(), new VideoProductionController.ShotContentRequest("画面A", null, null, null, null, 1L));
        Mono<VideoStoryboardEditService.ShotContentResult> second = editService.updateShotContent(ACCOUNT,
                seeded.shot2(), new VideoProductionController.ShotContentRequest(null, "旁白B", null, null, null, 1L));
        AtomicReference<Throwable> conflict = new AtomicReference<>();
        AtomicReference<VideoStoryboardEditService.ShotContentResult> firstOk = new AtomicReference<>();
        AtomicReference<VideoStoryboardEditService.ShotContentResult> secondOk = new AtomicReference<>();
        Mono.when(
                first.doOnSuccess(firstOk::set).onErrorResume(error -> {
                    conflict.set(error);
                    return Mono.empty();
                }),
                second.doOnSuccess(secondOk::set).onErrorResume(error -> {
                    conflict.set(error);
                    return Mono.empty();
                })).block(Duration.ofSeconds(10));
        // 两个都基于 v1，行锁+CAS 只允许一个成功
        long successes = (firstOk.get() != null ? 1 : 0) + (secondOk.get() != null ? 1 : 0);
        assertThat(successes).isEqualTo(1);
        assertThat(conflict.get()).isInstanceOf(com.grassland.intelligence.security.IntelligenceException.class);
        assertThat(editVersion(seeded.storyboardId())).isEqualTo(2L);

        // 旧客户端不带版本：兼容放行并提升版本（同一编辑闸）
        editService.updateShotContent(ACCOUNT, seeded.shot1(),
                new VideoProductionController.ShotContentRequest("旧客户端画面", null, null, null, null, null))
                .block(Duration.ofSeconds(10));
        assertThat(editVersion(seeded.storyboardId())).isEqualTo(3L);
        assertThat(shotVisual(seeded.shot1())).isEqualTo("旧客户端画面");
    }

    @Test
    @DisplayName("TC-008/009 服务端：409/404 均零写入（版本、内容不变）；committed 拒绝")
    void rejectsLeaveNoWrites() {
        Seeded seeded = seedStoryboard(2, "[]");

        // 版本不匹配 → 409 零写入
        client().put().uri("/api/video-production/shots/{id}/content", seeded.shot1())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("visual", "x", "expectedEditVersion", 99))
                .exchange().expectStatus().isEqualTo(409);
        assertThat(editVersion(seeded.storyboardId())).isEqualTo(1L);

        // 越权 → 404 零写入
        client().put().uri("/api/video-production/shots/{id}/content", seeded.shot1())
                .header("X-Grassland-Identity", sign(OTHER, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("visual", "x"))
                .exchange().expectStatus().isNotFound();

        // committed → 409
        db.sql("UPDATE video_storyboard SET status='committed' WHERE id=CAST(:id AS uuid)")
                .bind("id", seeded.storyboardId().toString()).then().block(Duration.ofSeconds(5));
        client().put().uri("/api/video-production/shots/{id}/content", seeded.shot1())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("visual", "y"))
                .exchange().expectStatus().isEqualTo(409);
        assertThat(shotVisual(seeded.shot1())).isEqualTo("画面");
        assertThat(editVersion(seeded.storyboardId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("API-06：批量编辑两镜原子生效版本只升一次；任一项非法整批不写")
    void batchEditAtomic() {
        Seeded seeded = seedStoryboard(2, "[\"data:image/png;base64,AA\"]");

        // 两镜批量：一次成功、版本 +1、updatedShotIds 两条
        client().patch().uri("/api/video-production/storyboards/{id}/content", seeded.storyboardId())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedEditVersion", 1, "patches", List.of(
                        Map.of("shotId", seeded.shot1(), "visual", "批量画面一", "anchorImageIndex", 1),
                        Map.of("shotId", seeded.shot2(), "narration", "批量旁白二"))))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.editVersion").isEqualTo(2)
                .jsonPath("$.data.updatedShotIds.length()").isEqualTo(2);
        assertThat(shotVisual(seeded.shot1())).isEqualTo("批量画面一");
        assertThat(shotPrompt(seeded.shot1())).isEqualTo("批量画面一");
        assertThat(shotNarration(seeded.shot2())).isEqualTo("批量旁白二");

        // 第二项非法（运镜不在枚举）：整批不写、版本不动
        client().patch().uri("/api/video-production/storyboards/{id}/content", seeded.storyboardId())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedEditVersion", 2, "patches", List.of(
                        Map.of("shotId", seeded.shot1(), "visual", "应被回滚的画面"),
                        Map.of("shotId", seeded.shot2(), "cameraMove", "不存在的运镜"))))
                .exchange().expectStatus().isBadRequest();
        assertThat(shotVisual(seeded.shot1())).isEqualTo("批量画面一");
        assertThat(editVersion(seeded.storyboardId())).isEqualTo(2L);

        // 版本不匹配 → 409
        client().patch().uri("/api/video-production/storyboards/{id}/content", seeded.storyboardId())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedEditVersion", 1, "patches",
                        List.of(Map.of("shotId", seeded.shot1(), "visual", "z"))))
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    @DisplayName("API-06 校验：缺版本/超 12 项/重复镜头/未知镜头/锚定图越界均 400 且零写入")
    void batchEditValidation() {
        Seeded seeded = seedStoryboard(2, "[]");

        // 缺 expectedEditVersion → 400
        client().patch().uri("/api/video-production/storyboards/{id}/content", seeded.storyboardId())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("patches", List.of(Map.of("shotId", seeded.shot1(), "visual", "x"))))
                .exchange().expectStatus().isBadRequest();

        // 空 patch（无内容字段）→ 400
        client().patch().uri("/api/video-production/storyboards/{id}/content", seeded.storyboardId())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedEditVersion", 1, "patches",
                        List.of(Map.of("shotId", seeded.shot1()))))
                .exchange().expectStatus().isBadRequest();

        // 重复镜头 → 400
        client().patch().uri("/api/video-production/storyboards/{id}/content", seeded.storyboardId())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedEditVersion", 1, "patches", List.of(
                        Map.of("shotId", seeded.shot1(), "visual", "a"),
                        Map.of("shotId", seeded.shot1(), "visual", "b"))))
                .exchange().expectStatus().isBadRequest();

        // 未知镜头 → 404
        client().patch().uri("/api/video-production/storyboards/{id}/content", seeded.storyboardId())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedEditVersion", 1, "patches",
                        List.of(Map.of("shotId", UUID.randomUUID(), "visual", "a"))))
                .exchange().expectStatus().isNotFound();

        // 锚定图序号超图片数（payload 0 张）→ 400
        client().patch().uri("/api/video-production/storyboards/{id}/content", seeded.storyboardId())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedEditVersion", 1, "patches",
                        List.of(Map.of("shotId", seeded.shot1(), "anchorImageIndex", 1))))
                .exchange().expectStatus().isBadRequest();

        // 13 项 → 400
        List<Map<String, Object>> oversized = new java.util.ArrayList<>();
        for (int i = 0; i < 13; i++) {
            oversized.add(Map.of("shotId", seeded.shot1(), "visual", "v" + i));
        }
        client().patch().uri("/api/video-production/storyboards/{id}/content", seeded.storyboardId())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedEditVersion", 1, "patches", oversized))
                .exchange().expectStatus().isBadRequest();

        assertThat(editVersion(seeded.storyboardId())).isEqualTo(1L);
        assertThat(shotVisual(seeded.shot1())).isEqualTo("画面");
    }

    @Test
    @DisplayName("API-04：增镜/删镜/分组带版本 CAS；不匹配 409 零写入")
    void collectionEditsCarryVersion() {
        Seeded seeded = seedStoryboard(3, "[]");

        // 增镜：正确版本 → 成功且版本 +1
        client().post().uri("/api/video-production/storyboards/{id}/shots", seeded.storyboardId())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("visual", "新增镜头", "expectedEditVersion", 1))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.editVersion").isEqualTo(2);

        // 增镜：过期版本 → 409
        client().post().uri("/api/video-production/storyboards/{id}/shots", seeded.storyboardId())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedEditVersion", 1))
                .exchange().expectStatus().isEqualTo(409);
        assertThat(editVersion(seeded.storyboardId())).isEqualTo(2L);

        // 分组：正确版本 → 成功且响应带新版本
        client().patch().uri("/api/video-production/storyboards/{id}/grouping", seeded.storyboardId())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("shots", List.of(), "branches",
                        List.of(Map.of("id", "b1", "name", "主版本", "shotIds", List.of())),
                        "expectedEditVersion", 2))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.editVersion").isEqualTo(3);

        // 删镜（query 带版本）：正确版本 → 成功；过期版本 → 409
        client().delete().uri("/api/video-production/shots/{id}?expectedEditVersion=3", seeded.shot2())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.editVersion").isEqualTo(4);
        client().delete().uri("/api/video-production/shots/{id}?expectedEditVersion=1", seeded.shot2())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isEqualTo(404); // 已删除
    }

    @Test
    @DisplayName("TC-009 服务端：晚到锚定图按启动版本 CAS——编辑后不落位、不覆盖新稿")
    void lateAnchorCoversNothingAfterEdit() {
        Seeded seeded = seedStoryboard(2, "[]");
        UUID anchorMedia = UUID.randomUUID();

        // 启动版本 1：版本未动 → 落位成功
        assertThat(shots.attachAnchorIfFresh(seeded.shot1(), anchorMedia, seeded.storyboardId(), 1L)
                .block(Duration.ofSeconds(5))).isTrue();
        assertThat(shotAnchorMedia(seeded.shot1())).isEqualTo(anchorMedia.toString());

        // 生成期间用户编辑（版本提升）：晚到结果按启动版本 CAS → 拒绝落位
        editService.updateShotContent(ACCOUNT, seeded.shot1(),
                new VideoProductionController.ShotContentRequest("编辑后的画面", null, null, null, null, null))
                .block(Duration.ofSeconds(10));
        UUID staleMedia = UUID.randomUUID();
        assertThat(shots.attachAnchorIfFresh(seeded.shot1(), staleMedia, seeded.storyboardId(), 1L)
                .block(Duration.ofSeconds(5))).isFalse();
        assertThat(shotAnchorMedia(seeded.shot1())).isEqualTo(anchorMedia.toString());
        assertThat(shotVisual(seeded.shot1())).isEqualTo("编辑后的画面");
    }

    @Test
    @DisplayName("冻结与编辑互斥（§7.2）：建任务后分镜 committed、编辑 409；committed 再建任务 409")
    void taskCreationFreezesAndInterlocksEditing() {
        Seeded seeded = seedStoryboard(2, "[]");
        // 可解析的视频渠道（sandbox）
        db.sql("""
                WITH cred AS (
                    INSERT INTO platform_provider_credential(name, provider, base_url,
                        encrypted_key, key_version, masked_hint, enabled)
                    VALUES ('it-edit-vg', 'sandbox', 'https://sandbox.invalid', NULL, 'v1', 'sk-***', true)
                    RETURNING id
                )
                INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
                    health_status, enabled, version, credential_id)
                SELECT 'video_generation', 'primary', 'sandbox', 'sandbox-video-v1', 'https://sandbox.invalid',
                    'healthy', true, 1, cred.id
                FROM cred
                """).then().block(Duration.ofSeconds(10));

        VideoProductionTask task = taskService
                .create(ACCOUNT, null, new VideoProductionTaskService.CreateRequest(seeded.storyboardId(), "op-edit-1"))
                .block(Duration.ofSeconds(20));
        assertThat(task).isNotNull();

        // 冻结后：编辑与增镜全部 409
        client().put().uri("/api/video-production/shots/{id}/content", seeded.shot1())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("visual", "冻结后画面"))
                .exchange().expectStatus().isEqualTo(409);
        client().post().uri("/api/video-production/storyboards/{id}/shots", seeded.storyboardId())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange().expectStatus().isEqualTo(409);

        // committed 再建任务 → 409（不重复派生共享 takes）
        client().post().uri("/api/video-production/tasks")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("storyboardId", seeded.storyboardId().toString(), "operationId", "op-edit-2"))
                .exchange().expectStatus().isEqualTo(409);
        Long taskRows = db.sql("SELECT COUNT(*) AS n FROM video_production_task")
                .map(row -> row.get("n", Long.class)).one().block(Duration.ofSeconds(5));
        assertThat(taskRows).isEqualTo(1L);
    }

    // ---------------- helpers ----------------

    private String shotPrompt(UUID shotId) {
        return shotColumn(shotId, "prompt");
    }

    private String shotVisual(UUID shotId) {
        return shotColumn(shotId, "visual");
    }

    private String shotNarration(UUID shotId) {
        return shotColumn(shotId, "narration");
    }

    private String shotAnchorMedia(UUID shotId) {
        return db.sql("SELECT anchor_media_id::text AS v FROM video_shot WHERE id=CAST(:id AS uuid)")
                .bind("id", shotId.toString())
                .map(row -> row.get("v", String.class)).one().block(Duration.ofSeconds(5));
    }

    private String shotColumn(UUID shotId, String column) {
        return db.sql("SELECT " + column + " AS v FROM video_shot WHERE id=CAST(:id AS uuid)")
                .bind("id", shotId.toString())
                .map(row -> row.get("v", String.class)).one().block(Duration.ofSeconds(5));
    }
}
