package com.grassland.intelligence.creationcanvas;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * 任务书 #100 C100-17（API-15 / §6.6）：计划原子应用。
 *
 * <p>
 * TC-038 两项修改版本闸（任一版本变化/任一动作失败全回滚，只成功一次升 editVersion；
 * planId 唯一结果重放）；TC-039 prepare-generation 只准备零媒体调用；TC-040 越界
 * update（未选中镜头）拒绝、跨账号 404。
 */
@DisplayName("Canvas agent apply (C100-17 / API-15)")
@TestPropertySource(properties = { "ai.video-generation.worker-enabled=false" })
class CanvasAgentApplyIT extends IntelligenceItSupport {

    private static final String ACCOUNT = "91919191-9191-4911-9191-919191919191";
    private static final String ACCOUNT_B = "92929292-9292-4921-9292-929292929292";

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM creation_canvas_agent_plan").then()
                .then(db.sql("DELETE FROM creation_canvas_document").then())
                .then(db.sql("DELETE FROM video_storyboard_variant").then())
                .then(db.sql("DELETE FROM video_storyboard_workspace").then())
                .then(db.sql("DELETE FROM video_shot_media_source").then())
                .then(db.sql("DELETE FROM video_shot").then())
                .then(db.sql("DELETE FROM video_storyboard").then())
                .then(db.sql("DELETE FROM creation_draft").then())
                .block(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("TC-038：两项合法修改原子应用只升一次 editVersion；版本漂移 409 零写入；planId 重放同结果")
    void atomicEditApplyWithVersionGate() {
        Prepared p = prepare();
        String action = "{\"edit\":{\"actions\":["
                + updateAction(p.shotIds.get(0), "新画面一")
                + "," + updateAction(p.shotIds.get(1), "新画面二")
                + "]}}";
        UUID planId = seedReadyPlan(p, action, List.of("shot:" + p.shotIds.get(0),
                "shot:" + p.shotIds.get(1)));

        Map<String, Object> applied = apply(planId, 200);
        assertThat(((Number) applied.get("editVersion")).longValue()).isEqualTo(2L);
        assertThat(visualOf(p.shotIds.get(0))).isEqualTo("新画面一");
        assertThat(visualOf(p.shotIds.get(1))).isEqualTo("新画面二");

        // 重放：同 planId 返回持久化的同一结果；业务不再变化
        Map<String, Object> replay = apply(planId, 200);
        assertThat(replay).isEqualTo(applied);
        assertThat(visualOf(p.shotIds.get(0))).isEqualTo("新画面一");

        // 版本漂移：计划基线落库后再提升 editVersion → 409 零写入
        UUID stale = seedReadyPlan(p, action, List.of("shot:" + p.shotIds.get(0)));
        db.sql("UPDATE video_storyboard SET edit_version = edit_version + 1 WHERE id=CAST(:id AS uuid)")
                .bind("id", p.storyboardId.toString()).then().block(Duration.ofSeconds(5));
        apply(stale, 409);
        assertThat(visualOf(p.shotIds.get(0))).isEqualTo("新画面一");

        // 任一失败全回滚：第二镜被并发删除 → 预校验失败，第一镜不写
        UUID third = seedReadyPlan(p, action, List.of("shot:" + p.shotIds.get(0),
                "shot:" + p.shotIds.get(1)));
        db.sql("DELETE FROM video_shot WHERE id=CAST(:id AS uuid)")
                .bind("id", p.shotIds.get(1).toString()).then().block(Duration.ofSeconds(5));
        String before = visualOf(p.shotIds.get(0));
        apply(third, 502);
        assertThat(visualOf(p.shotIds.get(0))).isEqualTo(before);
    }

    @Test
    @DisplayName("TC-039/040：prepare-generation 只返回准备参数（零媒体调用）；越界 update 拒绝；跨账号 404")
    void prepareOnlyAndScopeGuards() {
        Prepared p = prepare();
        UUID prepare = seedReadyPlan(p,
                "{\"prepare-generation\":{\"mode\":\"regenerate\",\"shotId\":\"" + p.shotIds.get(0) + "\"}}",
                List.of("shot:" + p.shotIds.get(0)));
        Map<String, Object> prepared = apply(prepare, 200);
        assertThat(prepared.get("preparedGeneration"))
                .isEqualTo(Map.of("mode", "regenerate", "shotId", p.shotIds.get(0).toString()));
        // 零媒体调用：任务表无行、镜头状态不变
        assertThat(countTasks(p.storyboardId)).isZero();
        assertThat(visualOf(p.shotIds.get(0))).isEqualTo("画面-1");

        // 越界 update：选中镜 1，却要改镜 2 → 502 拒绝（零写入）
        UUID cross = seedReadyPlan(p,
                "{\"edit\":{\"actions\":[" + updateAction(p.shotIds.get(1), "越界") + "]}}",
                List.of("shot:" + p.shotIds.get(0)));
        apply(cross, 502);
        assertThat(visualOf(p.shotIds.get(1))).isEqualTo("画面-2");

        // 跨账号 apply → 404
        client().post().uri("/api/creation-assistant/canvas/plans/{id}/apply", cross)
                .header("X-Grassland-Identity", sign(ACCOUNT_B, "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectStatus().isNotFound();
    }

    // ---- 帮手 ----

    private record Prepared(UUID storyboardId, UUID draftId, List<UUID> shotIds) {
    }

    private Prepared prepare() {
        UUID storyboardId = UUID.fromString(db.sql("INSERT INTO video_storyboard(account_id, "
                        + "target_duration_seconds, request_payload) VALUES (:account, 30, "
                        + "CAST(:payload AS jsonb)) RETURNING id::text")
                .bind("account", ACCOUNT)
                .bind("payload", "{\"images\":[\"data:image/png;base64,AAAA\"],\"shopName\":\"应用店\"}")
                .map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5)));
        List<UUID> shotIds = new java.util.ArrayList<>();
        for (int seq = 1; seq <= 3; seq++) {
            shotIds.add(UUID.fromString(db.sql("INSERT INTO video_shot(storyboard_id, seq, visual, "
                            + "narration, planned_seconds, camera_move, anchor_image_index, prompt) VALUES "
                            + "(CAST(:sb AS uuid), :seq, :visual, '旁白', 5, '固定机位', 1, 'p') "
                            + "RETURNING id::text")
                    .bind("sb", storyboardId.toString()).bind("seq", seq)
                    .bind("visual", "画面-" + seq)
                    .map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5))));
        }
        UUID draftId = UUID.fromString(db.sql("INSERT INTO creation_draft(id, owner_account_id, title, "
                        + "source_type, status, version, workspace_json) VALUES (gen_random_uuid(), :account, "
                        + "'应用草稿', 'independent', 'draft', 1, CAST(:workspace AS jsonb)) RETURNING id::text")
                .bind("account", ACCOUNT)
                .bind("workspace", "{\"schemaVersion\":1,\"capability\":\"video\",\"inputs\":{"
                        + "\"video\":{\"storyboardId\":\"" + storyboardId + "\"}}}")
                .map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5)));
        db.sql("INSERT INTO video_storyboard_workspace(storyboard_id, draft_id, account_id, operation_id, "
                        + "request_hash) VALUES (CAST(:sb AS uuid), CAST(:draft AS uuid), :account, "
                        + "gen_random_uuid(), :hash)")
                .bind("sb", storyboardId.toString()).bind("draft", draftId.toString())
                .bind("account", ACCOUNT).bind("hash", "c".repeat(64))
                .then().block(Duration.ofSeconds(5));
        db.sql("INSERT INTO creation_canvas_document(id, draft_id, account_id, schema_version, revision, "
                        + "document) VALUES (gen_random_uuid(), CAST(:draft AS uuid), :account, 1, 1, "
                        + "CAST(:document AS jsonb))")
                .bind("draft", draftId.toString()).bind("account", ACCOUNT)
                .bind("document", "{\"schemaVersion\":1,\"storyboardId\":\"" + storyboardId + "\","
                        + "\"viewport\":{\"panX\":0,\"panY\":0,\"scale\":1},\"nodes\":["
                        + "{\"id\":\"shot:" + shotIds.get(0) + "\",\"kind\":\"shot\",\"refType\":\"shot\","
                        + "\"refId\":\"" + shotIds.get(0) + "\",\"label\":null,\"text\":null,\"x\":40,\"y\":0},"
                        + "{\"id\":\"shot:" + shotIds.get(1) + "\",\"kind\":\"shot\",\"refType\":\"shot\","
                        + "\"refId\":\"" + shotIds.get(1) + "\",\"label\":null,\"text\":null,\"x\":360,\"y\":0}],"
                        + "\"edges\":[],\"activeBranchId\":null}")
                .then().block(Duration.ofSeconds(5));
        return new Prepared(storyboardId, draftId, shotIds);
    }

    private UUID seedReadyPlan(Prepared p, String actionJson, List<String> selectedNodeIds) {
        return UUID.fromString(db.sql("INSERT INTO creation_canvas_agent_plan(id, account_id, "
                        + "operation_id, request_hash, draft_id, storyboard_id, base_draft_version, "
                        + "base_edit_version, base_canvas_revision, status, selected_node_ids, instruction, "
                        + "summary, action, expires_at) VALUES (gen_random_uuid(), :account, "
                        + "gen_random_uuid(), :hash, CAST(:draft AS uuid), CAST(:sb AS uuid), "
                        + "(SELECT version FROM creation_draft WHERE id=CAST(:draft AS uuid)), "
                        + "(SELECT edit_version FROM video_storyboard WHERE id=CAST(:sb AS uuid)), 1, "
                        + "'ready', CAST(:selected AS jsonb), '指令', '摘要', CAST(:action AS jsonb), "
                        + "now() + interval '30 minutes') RETURNING id::text")
                .bind("account", ACCOUNT).bind("hash", "d".repeat(64))
                .bind("draft", p.draftId().toString()).bind("sb", p.storyboardId().toString())
                .bind("selected", toJsonArray(selectedNodeIds)).bind("action", actionJson)
                .map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5)));
    }

    private static String updateAction(UUID shotId, String visual) {
        return "{\"kind\":\"update-shot\",\"patch\":{\"shotId\":\"" + shotId + "\",\"visual\":\"" + visual
                + "\"}}";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> apply(UUID planId, int status) {
        Object[] holder = new Object[1];
        client().post().uri("/api/creation-assistant/canvas/plans/{id}/apply", planId)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectBody(String.class).consumeWith(result -> {
                    org.assertj.core.api.Assertions.assertThat(result.getStatus().value())
                            .as("apply 响应体=" + result.getResponseBody())
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

    private String visualOf(UUID shotId) {
        return db.sql("SELECT visual FROM video_shot WHERE id=CAST(:id AS uuid)")
                .bind("id", shotId.toString()).map(row -> row.get(0, String.class))
                .one().block(Duration.ofSeconds(5));
    }

    private long countTasks(UUID storyboardId) {
        return db.sql("SELECT COUNT(*) FROM video_production_task WHERE storyboard_id=CAST(:sb AS uuid)")
                .bind("sb", storyboardId.toString())
                .map(row -> row.get(0, Long.class)).one().block(Duration.ofSeconds(5));
    }

    private static String toJsonArray(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append('"').append(values.get(index)).append('"');
        }
        return json.append(']').toString();
    }
}
