package com.grassland.intelligence.creationcanvas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.ai.run.TextCompletionResult;
import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.videoproduction.VideoCompositionService;
import com.grassland.intelligence.videoproduction.VideoProductionTask;
import com.grassland.intelligence.videoproduction.VideoProductionTaskRepository;
import com.grassland.intelligence.videoproduction.VideoProductionTaskService;
import com.grassland.storage.ObjectStorageAdapter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
 * 任务书 #100 C100-19：五阶段联合链路（计划→应用→来源→制作→导出）同域集成。
 *
 * <p>
 * AC100-19：同源任务的 A/B 方案与部分自有素材——AI 修改 B（测试模型桩）→ 应用（真实
 * 编辑闸）→ 保存 own 来源（真实校验+ffprobe）→ 真实 FFmpeg 制作 → 导出 manifest 溯源；
 * A 方案内容/版本不变；跨账号读取全拒绝。环境无 ffmpeg 跳过（VideoCompositionIT 先例）。
 */
@DisplayName("Canvas workflow integration (C100-19)")
@TestPropertySource(properties = { "ai.video-generation.worker-enabled=false" })
class CanvasWorkflowIntegrationIT extends IntelligenceItSupport {

    private static final String ACCOUNT = "93939393-9393-4931-9393-939393939393";
    private static final String ACCOUNT_B = "94949494-9494-4941-9494-949494949494";
    private static final int SHOT_SECONDS = 5;

    @MockitoBean
    CreditsClient credits;

    @MockitoBean
    ObjectStorageAdapter storage;

    @MockitoBean
    FrozenTextExecutionService frozenText;

    @Autowired
    VideoProductionTaskService taskService;

    @Autowired
    VideoCompositionService composition;

    @Autowired
    VideoProductionTaskRepository taskRepo;

    private final Map<String, byte[]> objectStore = new ConcurrentHashMap<>();
    private final AtomicInteger reserveCount = new AtomicInteger();

    @BeforeAll
    static void requireFfmpeg() {
        assumeTrue(ffmpegAvailable(), "环境无 ffmpeg，跳过联合链路 IT");
    }

    @BeforeEach
    void cleanAndSeed() {
        reset(credits, storage, frozenText);
        reserveCount.set(0);
        when(credits.consume(anyString(), any(CreditFeature.class), anyString()))
                .thenAnswer(invocation -> {
                    reserveCount.incrementAndGet();
                    return Mono.just(new CreditCharge(invocation.getArgument(0),
                            invocation.getArgument(1), invocation.getArgument(2)));
                });
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
        when(storage.presignDownload(anyString(), anyLong(), any()))
                .thenAnswer(invocation -> java.net.URI.create("https://media.example.test/signed-att"));

        db.sql("DELETE FROM creation_canvas_agent_plan").then()
                .then(db.sql("DELETE FROM creation_canvas_document").then())
                .then(db.sql("DELETE FROM video_storyboard_variant").then())
                .then(db.sql("DELETE FROM video_shot_media_source").then())
                .then(db.sql("DELETE FROM video_storyboard_workspace").then())
                .then(db.sql("DELETE FROM video_production_task").then())
                .then(db.sql("DELETE FROM video_shot_take").then())
                .then(db.sql("DELETE FROM video_shot_audio").then())
                .then(db.sql("DELETE FROM video_shot").then())
                .then(db.sql("DELETE FROM video_storyboard").then())
                .then(db.sql("DELETE FROM creation_draft").then())
                .then(db.sql("DELETE FROM media_reference").then())
                // sandbox 能力行共库互斥（idx_platform_model_config_current 唯一）：
                // OwnMediaCompositionIT 同款清理，避免跨类残留顶住本类种子。
                .then(db.sql("DELETE FROM platform_model_config WHERE provider='sandbox' "
                        + "AND capability IN ('video_generation','video_tts')").then())
                .block(Duration.ofSeconds(10));

        db.sql("""
                WITH ins AS (
                    INSERT INTO platform_provider_credential(name, provider, base_url, enabled)
                    VALUES ('it-workflow-video', 'sandbox', 'https://video.sandbox.invalid', true)
                    ON CONFLICT DO NOTHING RETURNING id
                ), cred AS (
                    SELECT id FROM ins
                    UNION ALL
                    SELECT id FROM platform_provider_credential WHERE name = 'it-workflow-video'
                    LIMIT 1
                )
                INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
                    health_status, enabled, version, credential_id)
                SELECT 'video_generation', 'primary', 'sandbox', 'sandbox-video-v1',
                    'https://video.sandbox.invalid', 'healthy', true, 1, cred.id FROM cred
                """).then().block(Duration.ofSeconds(10));
        db.sql("""
                WITH ins AS (
                    INSERT INTO platform_provider_credential(name, provider, base_url, enabled)
                    VALUES ('it-workflow-tts', 'sandbox', 'https://tts.sandbox.invalid', true)
                    ON CONFLICT DO NOTHING RETURNING id
                ), cred AS (
                    SELECT id FROM ins
                    UNION ALL
                    SELECT id FROM platform_provider_credential WHERE name = 'it-workflow-tts'
                    LIMIT 1
                )
                INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
                    health_status, enabled, version, credential_id)
                SELECT 'video_tts', 'primary', 'sandbox', 'sandbox-tts-v1',
                    'https://tts.sandbox.invalid', 'healthy', true, 1, cred.id FROM cred
                """).then().block(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("AC100-19：A/B 方案 + 部分自有素材：AI 改 B → 应用 → own 来源 → 真实制作 → 联合导出溯源；A 不变")
    void fullChainFromPlanToExport() throws Exception {
        // ---- 源方案 A：3 镜 + 绑定 + 画布 + own 素材（带音轨双色 10s） ----
        byte[] ownMedia = twoToneMp4();
        UUID mediaId = seedOwnMedia("workflow/own.mp4", ownMedia);
        Prepared a = prepareStoryboardWithCanvas(3);

        // ---- 阶段0：A 先完成一次真实制作（同源任务的 A 方案基线） ----
        VideoProductionTask taskA = produce(a.storyboardId, "op-workflow-a", 3);
        UUID taskAFinalMedia = taskA.finalMediaId();
        long aVersionAfterProduce = editVersionOf(a.storyboardId);

        // ---- 阶段1：从已制作的 A 派生方案 B（TC-032 场景；A 保持既成状态） ----
        Map<String, Object> variantResult = postVariant(a, "方案B", editVersionOf(a.storyboardId), 200);
        String variantStoryboard = ((Map<?, ?>) variantResult.get("variant")).get("storyboardId").toString();
        String variantDraft = ((Map<?, ?>) variantResult.get("project")).get("id").toString();
        @SuppressWarnings("unchecked")
        Map<String, String> shotIdMap = (Map<String, String>) variantResult.get("shotIdMap");
        assertThat(variantStoryboard).isNotEqualTo(a.storyboardId.toString());
        assertThat(shotIdMap).hasSize(a.shotIds.size());
        List<UUID> bShots = a.shotIds.stream()
                .map(id -> UUID.fromString(shotIdMap.get(id.toString()))).toList();
        Prepared b = new Prepared(UUID.fromString(variantStoryboard),
                UUID.fromString(variantDraft), bShots);

        // ---- 阶段2：AI 计划修改 B 镜1（测试模型桩）+ 应用（真实编辑闸，B editVersion 2） ----
        stubModelAction("{\"edit\":{\"actions\":[{\"kind\":\"update-shot\",\"patch\":{\"shotId\":\""
                + b.shotIds.get(0) + "\",\"visual\":\"AI 改写的画面\"}}]}}");
        Map<String, Object> plan = postPlan(b, UUID.randomUUID(),
                List.of("shot:" + b.shotIds.get(0)), "把第一镜改得更抓人", 200);
        assertThat(plan.get("status")).isEqualTo("ready");
        Map<String, Object> applied = applyPlan(UUID.fromString(String.valueOf(plan.get("id"))), 200);
        assertThat(((Number) applied.get("editVersion")).longValue()).isEqualTo(2L);
        assertThat(visualOf(b.shotIds.get(0))).isEqualTo("AI 改写的画面");

        // ---- 阶段3：为 B 镜2 保存 own 来源（真实校验 + ffprobe 实测 10s 素材 [500,5500)） ----
        patchSources(b, Map.of("shotId", b.shotIds.get(1).toString(), "source",
                Map.of("kind", "own-media", "mediaId", mediaId.toString(), "trimStartMs", 500,
                        "trimEndMs", 500 + SHOT_SECONDS * 1000, "audioMode", "mute")), 200);

        // ---- 阶段4：B 真实制作（混合：镜1/3 generated + 镜2 own；sandbox take + TTS + FFmpeg） ----
        VideoProductionTask taskB = produce(b.storyboardId, "op-workflow-b", 2);
        assertThat(taskB.phase()).isEqualTo(VideoProductionTask.PHASE_SUCCEEDED);
        assertThat(taskB.actualCostCents()).isGreaterThan(0);
        assertThat(taskB.actualDurationSeconds()).isGreaterThan(0);

        // ---- 阶段5：联合导出（B 的任务）：manifest 声明每镜实际源，own 截取可追溯 ----
        client().get().uri("/api/video-production/tasks/{id}/export/bundle", taskB.id())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isOk();
        byte[] bundle = objectStore.get(
                com.grassland.intelligence.videoproduction.export.ExportBundleService
                        .bundleKey(taskB.id()));
        assertThat(bundle).isNotNull();
        List<String> entries = zipEntries(bundle);
        assertThat(entries).contains("bundle/manifest.json", "bundle/master.mp4", "bundle/subtitle.srt",
                "bundle/segments/shot-1.mp4", "bundle/segments/shot-2.mp4", "bundle/segments/shot-3.mp4");
        String manifest = zipText(bundle, "bundle/manifest.json");
        assertThat(manifest).contains("\"shotId\":\"" + b.shotIds.get(1) + "\"")
                .contains("\"mediaId\":\"" + mediaId + "\"")
                .contains("\"trimStartMs\":500")
                .contains("\"trimEndMs\":" + (500 + SHOT_SECONDS * 1000))
                .contains("\"audioMode\":\"mute\"");
        // 镜1/3 无来源行 → manifest 声明 generated（缺行补默认，不编造 own）
        assertThat(manifest).contains("\"shotId\":\"" + b.shotIds.get(0) + "\",\"source\":{\"kind\":\"generated\"}");
        assertThat(manifest).contains("\"shotId\":\"" + b.shotIds.get(2) + "\",\"source\":{\"kind\":\"generated\"}");
        // 成片真实（>10KB 的 MP4）且分镜稿含 AI 改写后的画面（编辑结果进入交付物）
        assertThat(zipBytes(bundle, "bundle/master.mp4").length)
                .as("master.mp4 应为真实 MP4").isGreaterThan(10_000);
        assertThat(zipText(bundle, "bundle/分镜稿.md")).contains("AI 改写的画面");

        // ---- 阶段6：A 不变（内容/版本/任务/来源四口径全冻结） ----
        assertThat(visualOf(a.shotIds.get(0))).isEqualTo("画面");
        assertThat(editVersionOf(a.storyboardId)).isEqualTo(aVersionAfterProduce);
        assertThat(taskRowsOf(a.storyboardId)).isEqualTo(1L);
        assertThat(taskRepo.findById(taskA.id(), ACCOUNT).block(Duration.ofSeconds(5)).finalMediaId())
                .isEqualTo(taskAFinalMedia);
        assertThat(sourceRowsOf(a.storyboardId)).isZero();

        // ---- 阶段7：跨账号全拒绝（B 分镜/计划/导出三口径，不泄露存在性） ----
        client().get().uri("/api/video-production/storyboards/{id}", b.storyboardId)
                .header("X-Grassland-Identity", sign(ACCOUNT_B, "recommender"))
                .exchange().expectStatus().isNotFound();
        client().get().uri("/api/creation-assistant/canvas/plans/{id}", plan.get("id"))
                .header("X-Grassland-Identity", sign(ACCOUNT_B, "recommender"))
                .exchange().expectStatus().isNotFound();
        client().get().uri("/api/video-production/tasks/{id}/export/bundle", taskB.id())
                .header("X-Grassland-Identity", sign(ACCOUNT_B, "recommender"))
                .exchange().expectStatus().isNotFound();
    }

    /**
     * 真实制作一段（A/B 共用）：generatedShots 为该分镜未挂 own 来源的镜头数
     * （候选 2 条/镜、narration 行 1 条/镜；own 镜零候选零 TTS）。返回 succeeded 终态任务。
     */
    private VideoProductionTask produce(UUID storyboardId, String operationId, int generatedShots)
            throws Exception {
        VideoProductionTask task = taskService
                .create(ACCOUNT, null, new VideoProductionTaskService.CreateRequest(storyboardId,
                        operationId))
                .block(Duration.ofSeconds(30));
        assertThat(task.mode()).isEqualTo(VideoProductionTask.MODE_VIDEO);
        // 仅 generated 镜头派生候选（own 镜零候选）
        assertThat(countTakes(storyboardId)).isEqualTo(generatedShots * 2L);
        for (var take : takeRows(storyboardId)) {
            byte[] mp4 = solidMp4("0x0000FF");
            String key = "media/video_take/" + take;
            objectStore.put(key, mp4);
            db.sql("INSERT INTO media_reference(id, owner_account_id, purpose, domain_type, domain_id, "
                            + "object_key, mime_type, size_bytes, source, status) VALUES (CAST(:id AS uuid), "
                            + ":account, 'video_take', 'video_shot_take', CAST(:id AS uuid), :key, "
                            + "'video/mp4', :size, 'generated', 'active')")
                    .bind("id", take).bind("account", ACCOUNT).bind("key", key).bind("size", mp4.length)
                    .then().block(Duration.ofSeconds(5));
            db.sql("UPDATE video_shot_take SET status='succeeded', media_id=CAST(:id AS uuid), "
                            + "duration_ms=5000 WHERE id=CAST(:id AS uuid)")
                    .bind("id", take).then().block(Duration.ofSeconds(5));
        }
        // 任务创建已为 narration 镜派生音频行（C100-12：own-mute 镜零行）——补媒体与终态
        assertThat(audioRows(storyboardId)).isEqualTo(generatedShots);
        for (String audioId : audioRowsOf(storyboardId)) {
            String mediaKey = "media/video_shot_audio/" + audioId;
            objectStore.put(mediaKey, sineWav());
            db.sql("INSERT INTO media_reference(id, owner_account_id, purpose, domain_type, domain_id, "
                            + "object_key, mime_type, size_bytes, source, status) VALUES (CAST(:id AS uuid), "
                            + ":account, 'video_shot_audio', 'video_shot_audio', CAST(:id AS uuid), :key, "
                            + "'audio/wav', 1000, 'generated', 'active') ON CONFLICT (id) DO NOTHING")
                    .bind("id", audioId).bind("account", ACCOUNT).bind("key", mediaKey)
                    .then().block(Duration.ofSeconds(5));
            db.sql("UPDATE video_shot_audio SET status='succeeded', media_id=CAST(:media AS uuid), "
                            + "duration_ms=5000, provider='sandbox', model='sandbox-tts-v1' "
                            + "WHERE id=CAST(:id AS uuid)")
                    .bind("media", audioId).bind("id", audioId).then().block(Duration.ofSeconds(5));
        }
        task = taskService.requestCompose(task.id(), ACCOUNT).block(Duration.ofSeconds(10));
        composition.compose(task).block(Duration.ofSeconds(180));
        VideoProductionTask done = taskRepo.findById(task.id(), ACCOUNT).block(Duration.ofSeconds(5));
        assertThat(done.phase()).isEqualTo(VideoProductionTask.PHASE_SUCCEEDED);
        assertThat(done.finalMediaId()).isNotNull();
        // own 镜零 TTS 行断言（生成调用为零口径）
        assertThat(audioRows(storyboardId)).isEqualTo(generatedShots);
        return done;
    }

    // ---- 帮手 ----

    static boolean ffmpegAvailable() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version").start();
            process.waitFor();
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException error) {
            return false;
        }
    }

    private record Prepared(UUID storyboardId, UUID draftId, List<UUID> shotIds) {
    }

    private Prepared prepareStoryboardWithCanvas(int shotCount) {
        UUID storyboardId = UUID.fromString(db.sql("INSERT INTO video_storyboard(account_id, "
                        + "target_duration_seconds, request_payload, grouping) VALUES (:account, 30, "
                        + "CAST(:payload AS jsonb), CAST('{\"shots\":[],\"branches\":[]}' AS jsonb)) "
                        + "RETURNING id::text")
                .bind("account", ACCOUNT)
                .bind("payload", "{\"images\":[\"data:image/png;base64,AAAA\"],\"shopName\":\"联合店\"}")
                .map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5)));
        List<UUID> shotIds = new ArrayList<>();
        for (int seq = 1; seq <= shotCount; seq++) {
            shotIds.add(UUID.fromString(db.sql("INSERT INTO video_shot(storyboard_id, seq, visual, "
                            + "narration, planned_seconds, camera_move, anchor_image_index, prompt) VALUES "
                            + "(CAST(:sb AS uuid), :seq, '画面', '旁白', :planned, '固定机位', 1, 'p') "
                            + "RETURNING id::text")
                    .bind("sb", storyboardId.toString()).bind("seq", seq).bind("planned", SHOT_SECONDS)
                    .map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5))));
        }
        UUID draftId = UUID.fromString(db.sql("INSERT INTO creation_draft(id, owner_account_id, title, "
                        + "source_type, status, version, workspace_json) VALUES (gen_random_uuid(), :account, "
                        + "'联合草稿', 'independent', 'draft', 1, CAST(:workspace AS jsonb)) RETURNING id::text")
                .bind("account", ACCOUNT)
                .bind("workspace", "{\"schemaVersion\":1,\"capability\":\"video\",\"inputs\":{"
                        + "\"video\":{\"storyboardId\":\"" + storyboardId + "\"}}}")
                .map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5)));
        db.sql("INSERT INTO video_storyboard_workspace(storyboard_id, draft_id, account_id, operation_id, "
                        + "request_hash) VALUES (CAST(:sb AS uuid), CAST(:draft AS uuid), :account, "
                        + "gen_random_uuid(), :hash)")
                .bind("sb", storyboardId.toString()).bind("draft", draftId.toString())
                .bind("account", ACCOUNT).bind("hash", "f".repeat(64))
                .then().block(Duration.ofSeconds(5));
        StringBuilder nodes = new StringBuilder();
        for (int index = 0; index < shotIds.size(); index++) {
            if (index > 0) {
                nodes.append(',');
            }
            nodes.append("{\"id\":\"shot:").append(shotIds.get(index)).append("\",\"kind\":\"shot\",")
                    .append("\"refType\":\"shot\",\"refId\":\"").append(shotIds.get(index))
                    .append("\",\"label\":null,\"text\":null,\"x\":").append(40 + index * 320).append(",\"y\":0}");
        }
        db.sql("INSERT INTO creation_canvas_document(id, draft_id, account_id, schema_version, revision, "
                        + "document) VALUES (gen_random_uuid(), CAST(:draft AS uuid), :account, 1, 1, "
                        + "CAST(:document AS jsonb))")
                .bind("draft", draftId.toString()).bind("account", ACCOUNT)
                .bind("document", "{\"schemaVersion\":1,\"storyboardId\":\"" + storyboardId
                        + "\",\"viewport\":{\"panX\":0,\"panY\":0,\"scale\":1},\"nodes\":[" + nodes
                        + "],\"edges\":[],\"activeBranchId\":null}")
                .then().block(Duration.ofSeconds(5));
        return new Prepared(storyboardId, draftId, shotIds);
    }

    @SuppressWarnings("unchecked")
    private void stubModelAction(String output) {
        when(frozenText.executeIndependent(any(), any(), anyInt(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    if (invocation.getArgument(1) == null || invocation.getArgument(5) == null) {
                        return Mono.never();
                    }
                    List<ChatMessage> messages = invocation.getArgument(1);
                    java.util.function.Function<TextCompletionResult, Object> transform =
                            (java.util.function.Function<TextCompletionResult, Object>) invocation
                                    .getArgument(5);
                    return Mono.just(new FrozenTextExecutionService.Traced<>(
                            transform.apply(new TextCompletionResult(output, 10, 5)),
                            UUID.randomUUID(), "qwen", "qwen-plus", 1, false));
                });
    }

    private UUID seedOwnMedia(String objectKey, byte[] content) {
        objectStore.put(objectKey, content);
        return UUID.fromString(db.sql("INSERT INTO media_reference(id, owner_account_id, purpose, "
                        + "object_key, mime_type, size_bytes, source, status) VALUES (gen_random_uuid(), "
                        + ":account, 'store-media', :objectKey, 'video/mp4', :size, 'upload', 'active') "
                        + "RETURNING id::text")
                .bind("account", ACCOUNT).bind("objectKey", objectKey).bind("size", content.length)
                .map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postPlan(Prepared p, UUID operationId, List<String> selected,
            String instruction, int status) {
        Object[] holder = new Object[1];
        client().post().uri("/api/creation-assistant/canvas/plans")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("operationId", operationId.toString(), "draftId", p.draftId.toString(),
                        "storyboardId", p.storyboardId.toString(), "selectedNodeIds", selected,
                        "expectedEditVersion", 1, "expectedCanvasRevision", 1, "instruction", instruction))
                .exchange().expectBody(String.class).consumeWith(result -> {
                    org.assertj.core.api.Assertions.assertThat(result.getStatus().value())
                            .as("postPlan=" + result.getResponseBody()).isEqualTo(status);
                    try {
                        holder[0] = ((Map<?, ?>) new com.fasterxml.jackson.databind.ObjectMapper()
                                .readValue(result.getResponseBody(), Map.class)).get("data");
                    } catch (Exception e) {
                        holder[0] = null;
                    }
                });
        return holder[0] == null ? Map.of() : (Map<String, Object>) holder[0];
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applyPlan(UUID planId, int status) {
        Object[] holder = new Object[1];
        client().post().uri("/api/creation-assistant/canvas/plans/{id}/apply", planId)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectBody(String.class).consumeWith(result -> {
                    org.assertj.core.api.Assertions.assertThat(result.getStatus().value())
                            .as("applyPlan=" + result.getResponseBody()).isEqualTo(status);
                    try {
                        holder[0] = ((Map<?, ?>) new com.fasterxml.jackson.databind.ObjectMapper()
                                .readValue(result.getResponseBody(), Map.class)).get("data");
                    } catch (Exception e) {
                        holder[0] = null;
                    }
                });
        return holder[0] == null ? Map.of() : (Map<String, Object>) holder[0];
    }

    private void patchSources(Prepared p, Map<String, Object> item, int status) {
        client().patch().uri("/api/video-production/storyboards/{id}/sources", p.storyboardId)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("sources", List.of(item)))
                .exchange().expectStatus().isEqualTo(status);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postVariant(Prepared p, String title, long expectedEditVersion, int status) {
        Object[] holder = new Object[1];
        client().post().uri("/api/video-production/storyboards/{id}/variants", p.storyboardId)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("operationId", UUID.randomUUID().toString(),
                        "expectedEditVersion", expectedEditVersion, "expectedDraftVersion", 1,
                        "title", title,
                        "shotIds", p.shotIds.stream().map(UUID::toString).toList()))
                .exchange().expectBody(String.class).consumeWith(result -> {
                    org.assertj.core.api.Assertions.assertThat(result.getStatus().value())
                            .as("postVariant=" + result.getResponseBody()).isEqualTo(status);
                    try {
                        holder[0] = ((Map<?, ?>) new com.fasterxml.jackson.databind.ObjectMapper()
                                .readValue(result.getResponseBody(), Map.class)).get("data");
                    } catch (Exception e) {
                        holder[0] = null;
                    }
                });
        return holder[0] == null ? Map.of() : (Map<String, Object>) holder[0];
    }

    private String visualOf(UUID shotId) {
        return db.sql("SELECT visual FROM video_shot WHERE id=CAST(:id AS uuid)")
                .bind("id", shotId.toString()).map(row -> row.get(0, String.class))
                .one().block(Duration.ofSeconds(5));
    }

    private long editVersionOf(UUID storyboardId) {
        return db.sql("SELECT edit_version FROM video_storyboard WHERE id=CAST(:id AS uuid)")
                .bind("id", storyboardId.toString()).map(row -> row.get(0, Long.class))
                .one().block(Duration.ofSeconds(5));
    }

    private long countTakes(UUID storyboardId) {
        return db.sql("SELECT COUNT(*) FROM video_shot_take t JOIN video_shot s ON s.id=t.shot_id "
                        + "WHERE s.storyboard_id=CAST(:sb AS uuid)")
                .bind("sb", storyboardId.toString())
                .map(row -> row.get(0, Long.class)).one().block(Duration.ofSeconds(5));
    }

    private long audioRows(UUID storyboardId) {
        return db.sql("SELECT COUNT(*) FROM video_shot_audio a JOIN video_shot s ON s.id=a.shot_id "
                        + "WHERE s.storyboard_id=CAST(:sb AS uuid)")
                .bind("sb", storyboardId.toString())
                .map(row -> row.get(0, Long.class)).one().block(Duration.ofSeconds(5));
    }

    private List<String> takeRows(UUID storyboardId) {
        return db.sql("SELECT t.id::text FROM video_shot_take t JOIN video_shot s ON s.id=t.shot_id "
                        + "WHERE s.storyboard_id=CAST(:sb AS uuid)")
                .bind("sb", storyboardId.toString())
                .map(row -> row.get(0, String.class)).all().collectList().block(Duration.ofSeconds(5));
    }

    private List<String> audioRowsOf(UUID storyboardId) {
        return db.sql("SELECT a.id::text FROM video_shot_audio a JOIN video_shot s ON s.id=a.shot_id "
                        + "WHERE s.storyboard_id=CAST(:sb AS uuid)")
                .bind("sb", storyboardId.toString())
                .map(row -> row.get(0, String.class)).all().collectList().block(Duration.ofSeconds(5));
    }

    private long taskRowsOf(UUID storyboardId) {
        return db.sql("SELECT COUNT(*) FROM video_production_task WHERE storyboard_id=CAST(:sb AS uuid)")
                .bind("sb", storyboardId.toString())
                .map(row -> row.get(0, Long.class)).one().block(Duration.ofSeconds(5));
    }

    private long sourceRowsOf(UUID storyboardId) {
        return db.sql("SELECT COUNT(*) FROM video_shot_media_source WHERE storyboard_id=CAST(:sb AS uuid)")
                .bind("sb", storyboardId.toString())
                .map(row -> row.get(0, Long.class)).one().block(Duration.ofSeconds(5));
    }

    // ---- zip 帮手（导出捆绑包断言；VideoExportBundleIT 同款约定） ----

    private static List<String> zipEntries(byte[] zipBytes) {
        List<String> names = new ArrayList<>();
        try (var zip = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(zipBytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
                zip.closeEntry();
            }
        } catch (Exception error) {
            throw new IllegalStateException("zip 解包失败", error);
        }
        return names;
    }

    private static byte[] zipBytes(byte[] zipBytes, String entryName) {
        try (var zip = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(zipBytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) {
                    return zip.readAllBytes();
                }
            }
        } catch (Exception error) {
            throw new IllegalStateException("zip 读取失败: " + entryName, error);
        }
        throw new IllegalStateException("zip 条目不存在: " + entryName);
    }

    private static String zipText(byte[] zipBytes, String entryName) {
        return new String(zipBytes(zipBytes, entryName), StandardCharsets.UTF_8);
    }

    /** 双色 10s（红 5s + 绿 5s）带 440Hz 音轨。 */
    private static byte[] twoToneMp4() throws IOException, InterruptedException {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("workflow-own", ".mp4");
        try {
            Process process = new ProcessBuilder("ffmpeg", "-loglevel", "error", "-y",
                    "-f", "lavfi", "-i", "color=c=0xFF0000:s=540x960:d=5:r=30",
                    "-f", "lavfi", "-i", "color=c=0x00FF00:s=540x960:d=5:r=30",
                    "-f", "lavfi", "-i", "sine=frequency=440:duration=10",
                    "-filter_complex", "[0:v][1:v]concat=n=2:v=1:a=0[v]",
                    "-map", "[v]", "-map", "2:a", "-shortest", "-pix_fmt", "yuv420p",
                    "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", file.toString())
                    .redirectErrorStream(false).start();
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            assertThat(process.exitValue()).as("双色素材生成失败: %s", stderr).isEqualTo(0);
            return java.nio.file.Files.readAllBytes(file);
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    private static byte[] solidMp4(String color) throws IOException, InterruptedException {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("workflow-solid", ".mp4");
        try {
            Process process = new ProcessBuilder("ffmpeg", "-loglevel", "error", "-y",
                    "-f", "lavfi", "-i", "color=c=" + color + ":s=540x960:d=" + SHOT_SECONDS + ":r=30",
                    "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", file.toString())
                    .redirectErrorStream(false).start();
            process.waitFor();
            return java.nio.file.Files.readAllBytes(file);
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    private static byte[] sineWav() throws IOException, InterruptedException {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("workflow-sine", ".wav");
        try {
            Process process = new ProcessBuilder("ffmpeg", "-loglevel", "error", "-y",
                    "-f", "lavfi", "-i", "sine=frequency=440:duration=5", file.toString())
                    .redirectErrorStream(false).start();
            process.waitFor();
            return java.nio.file.Files.readAllBytes(file);
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }
}
