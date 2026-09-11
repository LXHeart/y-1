package com.grassland.intelligence.creationcanvas;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * 任务书 #100 C100-09（API-08/09 / V73）：独立画布文档回归。
 *
 * <p>
 * 覆盖：TC-021 限额与伪造拒绝（200/201 节点、500/501 边、256KiB、重复 ID、未知字段、
 * kind↔refType 配对、canonical 跨项目）；TC-022 并发首建唯一/旧 revision 409/
 * 画布写不动草稿正文与版本/跨账号 404/归档只读。
 */
@DisplayName("Creation canvas document (C100-09 / API-08/09)")
@TestPropertySource(properties = { "ai.video-generation.worker-enabled=false" })
class CreationCanvasDocumentIT extends IntelligenceItSupport {

    private static final String ACCOUNT = "64646464-6464-6464-6464-646464646464";
    private static final String ACCOUNT_B = "65656565-6565-6565-6565-656565656565";

    @Autowired
    CreationCanvasRepository canvas;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM creation_canvas_document").then()
                .then(db.sql("DELETE FROM video_storyboard_workspace").then())
                .then(db.sql("DELETE FROM creation_draft").then())
                .then(db.sql("DELETE FROM video_shot_take").then())
                .then(db.sql("DELETE FROM video_shot").then())
                .then(db.sql("DELETE FROM video_storyboard").then())
                .block(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("TC-022：两标签页并发 revision=0 首建 → 唯一文档；后保存 409；草稿正文/version 不被布局写触碰")
    void concurrentFirstCreateKeepsSingleDocument() {
        Prepared p = prepare();
        Map<String, Object> first = putCanvas(p.draftId(), 0, canonicalBody(p));
        assertThat(((Number) first.get("revision")).longValue()).isEqualTo(1L);

        putCanvasExpect(p.draftId(), 0, canonicalBody(p), 409);
        assertThat(countRows("creation_canvas_document")).isEqualTo(1L);

        // 画布写不提升草稿 version、不改正文
        long draftVersion = draftVersion(p.draftId());
        String draftTitle = draftTitle(p.draftId());
        Map<String, Object> moved = canonicalBody(p);
        moved.put("viewport", viewport(10, 20));
        putCanvas(p.draftId(), 1, moved);
        assertThat(draftVersion(p.draftId())).isEqualTo(draftVersion);
        assertThat(draftTitle(p.draftId())).isEqualTo(draftTitle);

        // 旧 revision 更新 → 409（revision 已到 2）
        Map<String, Object> stale = canonicalBody(p);
        stale.put("viewport", viewport(30, 40));
        putCanvasExpect(p.draftId(), 1, stale, 409);
        Map<String, Object> loaded = getCanvas(p.draftId());
        assertThat(((Number) loaded.get("revision")).longValue()).isEqualTo(2L);
        @SuppressWarnings("unchecked")
        Map<String, Object> viewport = (Map<String, Object>) ((Map<String, Object>) loaded.get("document"))
                .get("viewport");
        assertThat(((Number) viewport.get("panX")).doubleValue()).isEqualTo(10d);
    }

    @Test
    @DisplayName("TC-021：200 节点合法上限成功；201 节点/256KiB 超限 400 且不部分写入")
    void nodeEdgeAndByteLimits() {
        Prepared p = prepare();
        Map<String, Object> atMax = canonicalBody(p);
        List<Map<String, Object>> nodes = new ArrayList<>(nodesOf(atMax));
        for (int i = 0; i < 198; i++) {
            nodes.add(node("note:" + uuidAt(0x10 + i), "note", "note", null, null, "备注" + i, 0, 0));
        }
        atMax.put("nodes", nodes);
        Map<String, Object> created = putCanvas(p.draftId(), 0, atMax);
        assertThat(((Number) created.get("revision")).longValue()).isEqualTo(1L);

        // 201 节点 → 400（另一草稿）
        Prepared q = prepare();
        Map<String, Object> over = canonicalBody(q);
        List<Map<String, Object>> overNodes = new ArrayList<>(nodesOf(over));
        for (int i = 0; i < 199; i++) {
            overNodes.add(node("note:" + uuidAt(0x30 + i), "note", "note", null, null, "备注" + i, 0, 0));
        }
        over.put("nodes", overNodes);
        putCanvasExpect(q.draftId(), 0, over, 400);
        assertThat(countRows("creation_canvas_document")).isEqualTo(1L); // 只有 p 的那份

        // 256KiB 超限：单 note text 撑过 262144 字节
        Prepared big = prepare();
        Map<String, Object> huge = canonicalBody(big);
        huge.put("nodes", List.of(
                node("brief:" + big.draftId(), "brief", "draft", big.draftId().toString(), null, null, 0, 0),
                node("note:" + uuidAt(0x50), "note", "note", null, null, "x".repeat(300_000), 0, 0)));
        putCanvasExpect(big.draftId(), 0, huge, 400);
    }

    @Test
    @DisplayName("TC-021：伪造 kind/refType/未知字段/重复 ID/悬空边/自环 → 400 CANVAS_INVALID_INPUT")
    void forgedStructuresRejected() {
        Prepared p = prepare();
        // kind↔refType 非法配对
        putCanvasExpect(p.draftId(), 0, bodyWithExtra(p,
                node("shot:shot-x", "brief", "shot", "shot-x", null, null, 0, 0)), 400);
        // canonical id 不一致（shot:{refId} 必须逐字匹配）
        putCanvasExpect(p.draftId(), 0, bodyWithExtra(p,
                node("shot:other", "shot", "shot", "shot-x", null, null, 0, 0)), 400);
        // 未知字段（额外塞 hidden）
        Map<String, Object> hidden = node("shot:" + p.shotIds().get(0), "shot", "shot",
                p.shotIds().get(0), null, null, 0, 0);
        hidden.put("hidden", true);
        putCanvasExpect(p.draftId(), 0, bodyWithExtra(p, hidden), 400);
        // 重复节点 ID
        Map<String, Object> dup = canonicalBody(p);
        List<Map<String, Object>> dupNodes = new ArrayList<>(nodesOf(dup));
        dupNodes.add(node("shot:" + p.shotIds().get(0), "shot", "shot", p.shotIds().get(0), null, null, 2, 2));
        dup.put("nodes", dupNodes);
        putCanvasExpect(p.draftId(), 0, dup, 400);
        // 悬空边
        putCanvasExpect(p.draftId(), 0, bodyWithEdge(p, edge("edge-1",
                "brief:" + p.draftId(), "shot:not-exist")), 400);
        // 自环（brief→brief 同节点）
        putCanvasExpect(p.draftId(), 0, bodyWithEdge(p, edge("edge-1",
                "brief:" + p.draftId(), "brief:" + p.draftId())), 400);
        assertThat(countRows("creation_canvas_document")).isEqualTo(0L);
    }

    @Test
    @DisplayName("TC-021：canonical 跨项目引用拒绝；不存在的 shot 是合法历史引用")
    void crossProjectCanonicalRejected() {
        Prepared p = prepare();
        Prepared other = prepare();
        // other 的 shot 节点放进 p 的画布 → 400（存在但归属其他分镜）
        putCanvasExpect(p.draftId(), 0, bodyWithExtra(p,
                node("shot:" + other.shotIds().get(0), "shot", "shot", other.shotIds().get(0), null, null, 0, 0)),
                400);
        // 不存在的 shot → 允许（历史失效引用保留位置）
        Map<String, Object> created = putCanvas(p.draftId(), 0, bodyWithExtra(p,
                node("shot:" + uuidAt(0x77), "shot", "shot", uuidAt(0x77), null, null, 5, 5)));
        assertThat(((Number) created.get("revision")).longValue()).isEqualTo(1L);
    }

    @Test
    @DisplayName("TC-022：无独立文档 GET null；跨账号 404；归档草稿 PUT 409 LOCKED；未绑定分镜 400")
    void ownershipArchivedAndBindingGuards() {
        Prepared p = prepare();
        // GET null（尚未升级）
        client().get().uri("/api/creation-drafts/{id}/canvas", p.draftId())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).consumeWith(result ->
                        assertThat(((Map<?, ?>) result.getResponseBody()).get("data")).isNull());
        // 跨账号 404（GET/PUT 同口径，不泄露归属）
        client().get().uri("/api/creation-drafts/{id}/canvas", p.draftId())
                .header("X-Grassland-Identity", sign(ACCOUNT_B, "recommender"))
                .exchange().expectStatus().isNotFound();
        putCanvasExpect(ACCOUNT_B, p.draftId(), 0, canonicalBody(p), 404);
        // 归档草稿只读
        db.sql("UPDATE creation_draft SET status='archived' WHERE id=CAST(:id AS uuid)")
                .bind("id", p.draftId().toString()).then().block(Duration.ofSeconds(5));
        putCanvasExpect(p.draftId(), 0, canonicalBody(p), 409);
        // 未绑定分镜的草稿：document.storyboardId 与草稿无关联 → 400
        Prepared unbound = prepareUnbound();
        Map<String, Object> foreign = canonicalBody(p);
        foreign.put("storyboardId", p.storyboardId());
        putCanvasExpect(unbound.draftId(), 0, foreign, 400);
    }

    // ---- 帮手 ----

    private record Prepared(UUID draftId, List<String> shotIds, String storyboardId) {
    }

    private Prepared prepare() {
        return prepare(true);
    }

    private Prepared prepareUnbound() {
        return prepare(false);
    }

    private Prepared prepare(boolean bind) {
        String storyboardId = db.sql("INSERT INTO video_storyboard(account_id, target_duration_seconds, "
                        + "request_payload) VALUES (:account, 25, CAST(:payload AS jsonb)) RETURNING id::text")
                .bind("account", ACCOUNT)
                .bind("payload", "{\"images\":[\"data:image/png;base64,AAAA\"],\"shopName\":\"店\"}")
                .map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5));
        List<String> shotIds = new ArrayList<>();
        for (int seq = 1; seq <= 2; seq++) {
            shotIds.add(db.sql("INSERT INTO video_shot(storyboard_id, seq, visual, narration, planned_seconds, "
                            + "camera_move, anchor_image_index, prompt) VALUES (CAST(:sb AS uuid), :seq, 'v', 'n', "
                            + "5, '固定机位', 1, 'p') RETURNING id::text")
                    .bind("sb", storyboardId).bind("seq", seq)
                    .map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5)));
        }
        UUID draftId = UUID.fromString(db.sql("INSERT INTO creation_draft(id, owner_account_id, title, "
                        + "source_type, status, version, workspace_json) VALUES (gen_random_uuid(), :account, "
                        + "'画布草稿', 'independent', 'draft', 1, CAST(:workspace AS jsonb)) RETURNING id::text")
                .bind("account", ACCOUNT)
                .bind("workspace", "{\"schemaVersion\":1,\"capability\":\"video\",\"inputs\":{\"video\":{"
                        + "\"storyboardId\":\"" + storyboardId + "\"}}}")
                .map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5)));
        if (bind) {
            db.sql("INSERT INTO video_storyboard_workspace(storyboard_id, draft_id, account_id, operation_id, "
                            + "request_hash) VALUES (CAST(:sb AS uuid), CAST(:draft AS uuid), :account, "
                            + "gen_random_uuid(), 'hash')")
                    .bind("sb", storyboardId).bind("draft", draftId.toString()).bind("account", ACCOUNT)
                    .then().block(Duration.ofSeconds(5));
        }
        return new Prepared(draftId, shotIds, storyboardId);
    }

    /** 节点构造：label/text/refId 允许 null（Map.of 拒绝 null 值）。 */
    private static Map<String, Object> node(String id, String kind, String refType, String refId, String label,
            String text, double x, double y) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("kind", kind);
        node.put("refType", refType);
        node.put("refId", refId);
        node.put("label", label);
        node.put("text", text);
        node.put("x", x);
        node.put("y", y);
        return node;
    }

    private static Map<String, Object> edge(String id, String from, String to) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", id);
        edge.put("kind", "reference");
        edge.put("fromNodeId", from);
        edge.put("toNodeId", to);
        return edge;
    }

    private static Map<String, Object> viewport(double panX, double panY) {
        Map<String, Object> viewport = new LinkedHashMap<>();
        viewport.put("panX", panX);
        viewport.put("panY", panY);
        viewport.put("scale", 1);
        return viewport;
    }

    private static Map<String, Object> document(String storyboardId, List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", 1);
        document.put("storyboardId", storyboardId);
        document.put("viewport", viewport(0, 0));
        document.put("nodes", nodes);
        document.put("edges", edges);
        document.put("activeBranchId", null);
        return document;
    }

    /** 最简合法画布：brief + 第一镜 shot 节点，无边。 */
    private Map<String, Object> canonicalBody(Prepared p) {
        return document(p.storyboardId(), List.of(
                node("brief:" + p.draftId(), "brief", "draft", p.draftId().toString(), null, null, 0, 0),
                node("shot:" + p.shotIds().get(0), "shot", "shot", p.shotIds().get(0), null, null, 40, 40)),
                List.of());
    }

    private Map<String, Object> bodyWithExtra(Prepared p, Map<String, Object> extraNode) {
        return document(p.storyboardId(), List.of(
                node("brief:" + p.draftId(), "brief", "draft", p.draftId().toString(), null, null, 0, 0),
                extraNode), List.of());
    }

    private Map<String, Object> bodyWithEdge(Prepared p, Map<String, Object> edge) {
        return document(p.storyboardId(), List.of(
                node("brief:" + p.draftId(), "brief", "draft", p.draftId().toString(), null, null, 0, 0),
                node("shot:" + p.shotIds().get(0), "shot", "shot", p.shotIds().get(0), null, null, 40, 40)),
                List.of(edge));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nodesOf(Map<String, Object> document) {
        return (List<Map<String, Object>>) document.get("nodes");
    }

    private Map<String, Object> getCanvas(UUID draftId) {
        Object[] holder = new Object[1];
        client().get().uri("/api/creation-drafts/{id}/canvas", draftId)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).consumeWith(result ->
                        holder[0] = ((Map<?, ?>) result.getResponseBody()).get("data"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) holder[0];
        return data;
    }

    private Map<String, Object> putCanvas(UUID draftId, long expectedRevision, Map<String, Object> document) {
        return putCanvasExpect(ACCOUNT, draftId, expectedRevision, document, 200);
    }

    private void putCanvasExpect(UUID draftId, long expectedRevision, Map<String, Object> document, int status) {
        putCanvasExpect(ACCOUNT, draftId, expectedRevision, document, status);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> putCanvasExpect(String account, UUID draftId, long expectedRevision,
            Map<String, Object> document, int status) {
        var result = client().put().uri("/api/creation-drafts/{id}/canvas", draftId)
                .header("X-Grassland-Identity", sign(account, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedRevision", expectedRevision, "document", document))
                .exchange().expectStatus().isEqualTo(status)
                .expectBody(String.class).returnResult();
        String raw = result.getResponseBody();
        try {
            Map<?, ?> envelope = raw == null ? null
                    : new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, Map.class);
            return envelope == null ? null : (Map<String, Object>) envelope.get("data");
        } catch (Exception e) {
            System.out.println("[putCanvasExpect 解析失败 status=" + status + "] " + raw);
            return null;
        }
    }

    private long draftVersion(UUID draftId) {
        return db.sql("SELECT version FROM creation_draft WHERE id=CAST(:id AS uuid)")
                .bind("id", draftId.toString()).map(row -> row.get(0, Long.class))
                .one().block(Duration.ofSeconds(5));
    }

    private String draftTitle(UUID draftId) {
        return db.sql("SELECT title FROM creation_draft WHERE id=CAST(:id AS uuid)")
                .bind("id", draftId.toString()).map(row -> row.get(0, String.class))
                .one().block(Duration.ofSeconds(5));
    }

    private long countRows(String table) {
        return db.sql("SELECT COUNT(*) FROM " + table).map(row -> row.get(0, Long.class))
                .one().block(Duration.ofSeconds(5));
    }

    /** 确定性测试 UUID（version 4 形态）。 */
    private static String uuidAt(long value) {
        return String.format("00000000-0000-4%03x-8000-%012d", value & 0xfff, value);
    }
}
