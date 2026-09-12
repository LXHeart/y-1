package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * 任务书 #100 C100-14（API-11/12 / V75）：独立方案派生回归。
 *
 * <p>
 * TC-032：部分镜头按序派生 → 新 draft/sb/shot 全独立 ID；同键重放同一份；第 21 个派生拒绝；
 * 中途失败全回滚（超长标题约束在谱系行插入时失败）；无模型/计费调用（零 take/零任务复制）。
 * TC-033：源有任务快照/分组/来源/画布 → ID 映射与 workspace 正确；不复制旧 task/takes/result；
 * 源版本变化后旧请求（新键）409。
 */
@DisplayName("Video storyboard variant (C100-14 / API-11/12)")
@TestPropertySource(properties = { "ai.video-generation.worker-enabled=false" })
class VideoStoryboardVariantIT extends IntelligenceItSupport {

    private static final String ACCOUNT = "88888888-8888-4888-8888-888888888888";

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM creation_canvas_document").then()
                .then(db.sql("DELETE FROM video_storyboard_variant").then())
                .then(db.sql("DELETE FROM video_shot_media_source").then())
                .then(db.sql("DELETE FROM video_storyboard_workspace").then())
                .then(db.sql("DELETE FROM video_production_task").then())
                .then(db.sql("DELETE FROM video_shot_take").then())
                .then(db.sql("DELETE FROM video_shot").then())
                .then(db.sql("DELETE FROM video_storyboard").then())
                .then(db.sql("DELETE FROM creation_draft").then())
                .then(db.sql("DELETE FROM media_reference").then())
                .block(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("TC-032/033：部分镜头派生 → 全独立 ID、来源/画布重映射、不复制运行；同键重放幂等")
    void deriveSubsetWithFullIsolation() {
        Prepared p = prepareSourceWithEverything();

        List<UUID> subset = List.of(p.shotIds.get(1), p.shotIds.get(0)); // 逆序选择 → 新 seq 按选择顺序
        UUID operation = UUID.randomUUID();
        Map<String, Object> first = derive(p.storyboardId, operation, 1L, 1L, "方案B", subset, 200);

        @SuppressWarnings("unchecked")
        Map<String, String> shotIdMap = (Map<String, String>) first.get("shotIdMap");
        assertThat(shotIdMap).hasSize(2);
        assertThat(shotIdMap.get(p.shotIds.get(0).toString())).isNotEqualTo(p.shotIds.get(0).toString());
        assertThat(shotIdMap.get(p.shotIds.get(1).toString())).isNotEqualTo(p.shotIds.get(1).toString());

        String variantStoryboardId = storyboardIdOf(first);
        // 新分镜两镜、seq 按选择顺序（1=源第2镜内容）
        List<Map<String, Object>> variantShots = shotsOf(variantStoryboardId);
        assertThat(variantShots).hasSize(2);
        assertThat(variantShots.get(0).get("visual")).isEqualTo("画面-2");
        assertThat(variantShots.get(1).get("visual")).isEqualTo("画面-1");

        // 来源复制（mediaId 共享、shotId 重映射）
        long variantSources = count("video_shot_media_source", "storyboard_id=CAST('"
                + variantStoryboardId + "' AS uuid)");
        assertThat(variantSources).isEqualTo(1L); // 只有镜 2 有 own 来源
        // 不复制运行/选择/成片：变体分镜零 take 零任务；源分镜原样（3 take 行还在）
        assertThat(countTakeRows(variantStoryboardId)).isZero();
        assertThat(count("video_production_task", "storyboard_id=CAST('" + variantStoryboardId
                + "' AS uuid)")).isZero();
        assertThat(countTakeRows(p.storyboardId.toString())).isEqualTo(3L);

        // 画布文档：brief 指向新草稿、shot 重映射、take/delivery 节点剔除
        String variantDraftId = draftIdOf(first);
        String canvasJson = db.sql("SELECT document::text FROM creation_canvas_document "
                        + "WHERE draft_id=(SELECT draft_id FROM video_storyboard_workspace "
                        + "WHERE storyboard_id=CAST(:sb AS uuid))")
                .bind("sb", variantStoryboardId)
                .map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5));
        assertThat(canvasJson).contains("brief:" + variantDraftId);
        assertThat(canvasJson).contains("shot:" + shotIdMap.get(p.shotIds.get(0).toString()));
        assertThat(canvasJson).doesNotContain("take:").doesNotContain("delivery:");

        // workspace 重映射：inputs.video.storyboardId=新分镜；不带 productionTaskId/delivery
        String workspaceJson = db.sql("SELECT workspace_json::text FROM creation_draft "
                        + "WHERE id=CAST(:id AS uuid)")
                .bind("id", variantDraftId)
                .map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5));
        assertThat(workspaceJson).contains(variantStoryboardId);
        assertThat(workspaceJson).doesNotContain("productionTaskId").doesNotContain("delivery");

        // 同键同参重放 → 同一结果（不产生第二份）
        Map<String, Object> replay = derive(p.storyboardId, operation, 1L, 1L, "方案B", subset, 200);
        assertThat(storyboardIdOf(replay)).isEqualTo(variantStoryboardId);
        assertThat(countRows("video_storyboard_variant")).isEqualTo(1L);
        // 同键异参 → 409
        derive(p.storyboardId, operation, 1L, 1L, "方案C", subset, 409);

        // C100-20 契约补缺（§6.1）：VariantSummary 必须带 draftId/createdAt（前端 switchTo
        // 依赖 draftId 导航；此前仅前端类型声明、服务端不回，属契约错位）
        @SuppressWarnings("unchecked")
        Map<String, Object> variantSummary = (Map<String, Object>) first.get("variant");
        assertThat(variantSummary.get("draftId")).isEqualTo(variantDraftId);
        assertThat(variantSummary.get("createdAt")).isNotNull();
    }

    @Test
    @DisplayName("C100-19 回归：源分镜无 grouping（NULL）也可派生（R2DBC bindNull 路径）")
    void deriveFromNullGroupingSource() {
        // 最小源：分镜（grouping NULL——AI 生成分镜的常态）+ 1 镜 + 草稿 + 绑定
        UUID storyboardId = UUID.fromString(db.sql("INSERT INTO video_storyboard(account_id, "
                        + "target_duration_seconds, request_payload) VALUES (:account, 30, "
                        + "CAST(:payload AS jsonb)) RETURNING id::text")
                .bind("account", ACCOUNT)
                .bind("payload", "{\"images\":[],\"shopName\":\"无分组店\"}")
                .map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5)));
        UUID shotId = UUID.fromString(db.sql("INSERT INTO video_shot(storyboard_id, seq, visual, narration, "
                        + "planned_seconds, camera_move, anchor_image_index, prompt) VALUES "
                        + "(CAST(:sb AS uuid), 1, '画面', '旁白', 5, '固定机位', 1, 'p') RETURNING id::text")
                .bind("sb", storyboardId.toString())
                .map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5)));
        UUID draftId = UUID.fromString(db.sql("INSERT INTO creation_draft(id, owner_account_id, title, "
                        + "source_type, status, version, workspace_json) VALUES (gen_random_uuid(), :account, "
                        + "'无分组草稿', 'independent', 'draft', 1, CAST(:workspace AS jsonb)) RETURNING id::text")
                .bind("account", ACCOUNT)
                .bind("workspace", "{\"schemaVersion\":1,\"capability\":\"video\",\"inputs\":"
                        + "{\"video\":{\"storyboardId\":\"" + storyboardId + "\"}}}")
                .map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5)));
        db.sql("INSERT INTO video_storyboard_workspace(storyboard_id, draft_id, account_id, operation_id, "
                        + "request_hash) VALUES (CAST(:sb AS uuid), CAST(:draft AS uuid), :account, "
                        + "gen_random_uuid(), :hash)")
                .bind("sb", storyboardId.toString()).bind("draft", draftId.toString())
                .bind("account", ACCOUNT).bind("hash", "0".repeat(64))
                .then().block(Duration.ofSeconds(5));

        Map<String, Object> result = derive(storyboardId, UUID.randomUUID(), 1L, 1L, "无分组派生",
                List.of(shotId), 200);
        assertThat(storyboardIdOf(result)).isNotEqualTo(storyboardId.toString());
        // 派生分镜 grouping 也为 NULL（无分组源不凭空造空结构；NULL 列不能经 map 返 null）
        Boolean variantGroupingIsNull = db.sql(
                        "SELECT grouping IS NULL FROM video_storyboard WHERE id=CAST(:id AS uuid)")
                .bind("id", storyboardIdOf(result))
                .map(row -> row.get(0, Boolean.class)).one().block(Duration.ofSeconds(5));
        assertThat(variantGroupingIsNull).isTrue();
    }

    @Test
    @DisplayName("TC-032：源版本变化后旧请求 409；中途失败全回滚；第 21 个派生拒绝")
    void versionGateRollbackAndCap() {
        Prepared p = prepareSourceWithEverything();
        List<UUID> subset = List.of(p.shotIds.get(0));

        // 源 editVersion 已提升 → 旧 expectedEditVersion 409（新键重试旧请求）
        db.sql("UPDATE video_storyboard SET edit_version=2 WHERE id=CAST(:id AS uuid)")
                .bind("id", p.storyboardId.toString()).then().block(Duration.ofSeconds(5));
        derive(p.storyboardId, UUID.randomUUID(), 1L, 1L, "旧请求", subset, 409);
        db.sql("UPDATE video_storyboard SET edit_version=1 WHERE id=CAST(:id AS uuid)")
                .bind("id", p.storyboardId.toString()).then().block(Duration.ofSeconds(5));

        // 中途失败全回滚：把源画布文档 nodes 改成损坏结构 → 谱系/草稿/分镜/镜头已插入后
        // 画布重映射阶段抛错——事务必须整体回滚（§7.2）
        long damaged = db.sql("UPDATE creation_canvas_document SET document=CAST('{\"nodes\":3}' AS jsonb) "
                        + "WHERE draft_id=CAST(:draft AS uuid)")
                .bind("draft", p.draftId().toString()).fetch().rowsUpdated().block(Duration.ofSeconds(5));
        assertThat(damaged).as("源画布文档应存在并被损坏").isEqualTo(1L);
        derive(p.storyboardId, UUID.randomUUID(), 1L, 1L, "中途失败", subset, 500);
        assertThat(countRows("video_storyboard")).isEqualTo(1L); // 只剩源分镜
        assertThat(countRows("creation_draft")).isEqualTo(1L);
        assertThat(countRows("video_storyboard_variant")).isEqualTo(0L);
        db.sql("DELETE FROM creation_canvas_document WHERE draft_id=CAST(:draft AS uuid)")
                .bind("draft", p.draftId().toString()).then().block(Duration.ofSeconds(5));

        // 上限：预插 20 个派生 → 第 21 个 400 LIMIT
        for (int i = 0; i < 20; i++) {
            UUID childSb = UUID.randomUUID();
            db.sql("INSERT INTO video_storyboard(id, account_id, target_duration_seconds, request_payload) "
                            + "VALUES (CAST(:id AS uuid), :account, 30, CAST('{\"images\":[]}' AS jsonb))")
                    .bind("id", childSb.toString()).bind("account", ACCOUNT).then().block(Duration.ofSeconds(5));
            db.sql("INSERT INTO video_storyboard_variant(storyboard_id, parent_storyboard_id, "
                            + "root_storyboard_id, account_id, operation_id, request_hash, "
                            + "source_edit_version, source_draft_version, title, shot_id_map) "
                            + "VALUES (CAST(:sb AS uuid), CAST(:parent AS uuid), CAST(:parent AS uuid), "
                            + ":account, gen_random_uuid(), :hash, 1, 1, :title, '{}'::jsonb)")
                    .bind("sb", childSb.toString()).bind("parent", p.storyboardId.toString())
                    .bind("account", ACCOUNT).bind("hash", "f".repeat(64))
                    .bind("title", "预置" + i).then().block(Duration.ofSeconds(5));
        }
        derive(p.storyboardId, UUID.randomUUID(), 1L, 1L, "第21个", subset, 400);

        // 列表：根 + 20 派生
        Object[] holder = new Object[1];
        client().get().uri("/api/video-production/storyboards/{id}/variants", p.storyboardId)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).consumeWith(result -> {
                    Map<?, ?> envelope = result.getResponseBody();
                    holder[0] = ((Map<?, ?>) envelope.get("data")).get("items");
                });
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) holder[0];
        assertThat(items).hasSize(21); // 根 + 20
        // C100-20 契约补缺：根行带自身绑定草稿与 createdAt；谱系行 createdAt 落在
        // variant 行（预置派生无绑定 → draftId null 防御位，画布真实流必建绑定）
        Map<String, Object> rootRow = items.get(0);
        assertThat(rootRow.get("storyboardId")).isEqualTo(p.storyboardId.toString());
        assertThat(rootRow.get("draftId")).isEqualTo(p.draftId().toString());
        assertThat(rootRow.get("createdAt")).isNotNull();
        for (int i = 1; i < items.size(); i++) {
            assertThat(items.get(i).get("createdAt")).as("谱系行 %d createdAt", i).isNotNull();
        }
    }

    // ---- 帮手 ----

    private record Prepared(UUID storyboardId, List<UUID> shotIds, UUID draftId) {
    }

    /** 源方案：3 镜（镜2 own 来源）+ 绑定草稿 + 3 take 行（模拟已制作）+ 画布文档。 */
    private Prepared prepareSourceWithEverything() {
        UUID storyboardId = UUID.fromString(db.sql("INSERT INTO video_storyboard(account_id, "
                        + "target_duration_seconds, request_payload, grouping) VALUES (:account, 30, "
                        + "CAST(:payload AS jsonb), CAST(:grouping AS jsonb)) RETURNING id::text")
                .bind("account", ACCOUNT)
                .bind("payload", "{\"images\":[\"data:image/png;base64,AAAA\"],\"shopName\":\"方案源\"}")
                .bind("grouping", "{\"shots\":[],\"branches\":[]}")
                .map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5)));
        List<UUID> shotIds = new ArrayList<>();
        for (int seq = 1; seq <= 3; seq++) {
            shotIds.add(UUID.fromString(db.sql("INSERT INTO video_shot(storyboard_id, seq, visual, narration, "
                            + "planned_seconds, camera_move, anchor_image_index, prompt) VALUES "
                            + "(CAST(:sb AS uuid), :seq, :visual, :narration, 5, '固定机位', 1, '提示词') "
                            + "RETURNING id::text")
                    .bind("sb", storyboardId.toString()).bind("seq", seq)
                    .bind("visual", "画面-" + seq).bind("narration", "旁白-" + seq)
                    .map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5))));
        }
        UUID mediaId = UUID.fromString(db.sql("INSERT INTO media_reference(id, owner_account_id, purpose, "
                        + "object_key, mime_type, size_bytes, source, status) VALUES (gen_random_uuid(), "
                        + ":account, 'store-media', 'variant/own.mp4', 'video/mp4', 1000, 'upload', 'active') "
                        + "RETURNING id::text")
                .bind("account", ACCOUNT)
                .map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5)));
        db.sql("INSERT INTO video_shot_media_source(shot_id, storyboard_id, source_kind, media_id, "
                        + "trim_start_ms, trim_end_ms, audio_mode) VALUES (CAST(:shot AS uuid), "
                        + "CAST(:sb AS uuid), 'own-media', CAST(:media AS uuid), 0, 5000, 'mute')")
                .bind("shot", shotIds.get(1).toString()).bind("sb", storyboardId.toString())
                .bind("media", mediaId.toString()).then().block(Duration.ofSeconds(5));
        UUID draftId = UUID.fromString(db.sql("INSERT INTO creation_draft(id, owner_account_id, title, "
                        + "source_type, status, version, workspace_json) VALUES (gen_random_uuid(), :account, "
                        + "'源草稿', 'independent', 'draft', 1, CAST(:workspace AS jsonb)) RETURNING id::text")
                .bind("account", ACCOUNT)
                .bind("workspace", "{\"schemaVersion\":1,\"capability\":\"video\",\"currentStep\":\"compose\","
                        + "\"inputs\":{\"video\":{\"storyboardId\":\"" + storyboardId
                        + "\",\"productionTaskId\":\"legacy-task\"}},"
                        + "\"delivery\":{\"summary\":\"旧交付\"}}")
                .map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5)));
        db.sql("INSERT INTO video_storyboard_workspace(storyboard_id, draft_id, account_id, operation_id, "
                        + "request_hash) VALUES (CAST(:sb AS uuid), CAST(:draft AS uuid), :account, "
                        + "gen_random_uuid(), :hash)")
                .bind("sb", storyboardId.toString()).bind("draft", draftId.toString())
                .bind("account", ACCOUNT).bind("hash", "a".repeat(64))
                .then().block(Duration.ofSeconds(5));
        db.sql("INSERT INTO creation_canvas_document(id, draft_id, account_id, schema_version, revision, document) "
                        + "VALUES (gen_random_uuid(), CAST(:draft AS uuid), :account, 1, 1, CAST(:document AS jsonb))")
                .bind("draft", draftId.toString()).bind("account", ACCOUNT)
                .bind("document", "{\"schemaVersion\":1,\"storyboardId\":\"" + storyboardId + "\","
                        + "\"viewport\":{\"panX\":0,\"panY\":0,\"scale\":1},"
                        + "\"nodes\":[{\"id\":\"brief:" + draftId + "\",\"kind\":\"brief\",\"refType\":\"draft\","
                        + "\"refId\":\"" + draftId + "\",\"label\":null,\"text\":null,\"x\":0,\"y\":0},"
                        + "{\"id\":\"shot:" + shotIds.get(0) + "\",\"kind\":\"shot\",\"refType\":\"shot\","
                        + "\"refId\":\"" + shotIds.get(0) + "\",\"label\":null,\"text\":null,\"x\":40,\"y\":0},"
                        + "{\"id\":\"shot:" + shotIds.get(1) + "\",\"kind\":\"shot\",\"refType\":\"shot\","
                        + "\"refId\":\"" + shotIds.get(1) + "\",\"label\":null,\"text\":null,\"x\":360,\"y\":0},"
                        + "{\"id\":\"take:legacy-take\",\"kind\":\"take\",\"refType\":\"take\","
                        + "\"refId\":\"legacy-take\",\"label\":null,\"text\":null,\"x\":40,\"y\":300}],"
                        + "\"edges\":[],\"activeBranchId\":null}")
                .then().block(Duration.ofSeconds(5));
        // 模拟已制作：3 条 take 行（变体不得复制）
        for (UUID shotId : shotIds) {
            db.sql("INSERT INTO video_shot_take(shot_id, take_no, provider, model, status) "
                            + "VALUES (CAST(:shot AS uuid), 1, 'sandbox', 'm', 'succeeded')")
                    .bind("shot", shotId.toString()).then().block(Duration.ofSeconds(5));
        }
        return new Prepared(storyboardId, shotIds, draftId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> derive(UUID storyboardId, UUID operationId, Long editVersion,
            Long draftVersion, String title, List<UUID> shotIds, int status) {
        Object[] holder = new Object[1];
        client().post().uri("/api/video-production/storyboards/{id}/variants", storyboardId)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("operationId", operationId.toString(),
                        "expectedEditVersion", editVersion,
                        "expectedDraftVersion", draftVersion,
                        "title", title,
                        "shotIds", shotIds.stream().map(UUID::toString).toList()))
                .exchange().expectBody(String.class).consumeWith(result -> {
                    org.assertj.core.api.Assertions.assertThat(result.getStatus().value())
                            .as("derive 响应体=" + result.getResponseBody())
                            .isEqualTo(status);
                    try {
                        Map<?, ?> envelope = result.getResponseBody() == null ? null
                                : new com.fasterxml.jackson.databind.ObjectMapper()
                                        .readValue(result.getResponseBody(), Map.class);
                        holder[0] = envelope == null ? null : envelope.get("data");
                    } catch (Exception e) {
                        holder[0] = null;
                    }
                });
        return holder[0] == null ? Map.of() : (Map<String, Object>) holder[0];
    }

    private static String storyboardIdOf(Map<String, Object> result) {
        @SuppressWarnings("unchecked")
        Map<String, Object> variant = (Map<String, Object>) result.get("variant");
        return (String) variant.get("storyboardId");
    }

    private static String draftIdOf(Map<String, Object> result) {
        @SuppressWarnings("unchecked")
        Map<String, Object> project = (Map<String, Object>) result.get("project");
        return (String) project.get("id");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> shotsOf(String storyboardId) {
        return db.sql("SELECT visual FROM video_shot WHERE storyboard_id=CAST(:sb AS uuid) ORDER BY seq")
                .bind("sb", storyboardId)
                .map(row -> Map.<String, Object>of("visual", row.get(0, String.class)))
                .all().collectList().block(Duration.ofSeconds(5));
    }

    private long countTakeRows(String storyboardId) {
        return db.sql("SELECT COUNT(*) FROM video_shot_take t JOIN video_shot s ON s.id=t.shot_id "
                        + "WHERE s.storyboard_id=CAST(:sb AS uuid)")
                .bind("sb", storyboardId)
                .map(row -> row.get(0, Long.class)).one().block(Duration.ofSeconds(5));
    }

    private long count(String table, String where) {
        return db.sql("SELECT COUNT(*) FROM " + table + " WHERE " + where)
                .map(row -> row.get(0, Long.class)).one().block(Duration.ofSeconds(5));
    }

    private long countRows(String table) {
        return db.sql("SELECT COUNT(*) FROM " + table).map(row -> row.get(0, Long.class))
                .one().block(Duration.ofSeconds(5));
    }
}
