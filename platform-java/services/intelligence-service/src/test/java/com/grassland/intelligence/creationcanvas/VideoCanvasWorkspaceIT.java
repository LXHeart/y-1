package com.grassland.intelligence.creationcanvas;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * 任务书 #100 C100-04（API-07 / V72）：分镜 ↔ 草稿唯一关联回归（TC-010 服务端面）。
 *
 * <p>
 * 覆盖：旧 storyboard-only 深链唯一补关联且不新建制作任务、同键重放返回同一关联、
 * 分镜已有关联不迁移、带 draftId 的版本 CAS 与归属校验、存量多候选 409、
 * 跨账号 404、关联草稿被删 409、最小草稿不携带 base64。
 */
@DisplayName("Video canvas workspace binding (C100-04 / API-07)")
@TestPropertySource(properties = { "ai.video-generation.worker-enabled=false" })
class VideoCanvasWorkspaceIT extends IntelligenceItSupport {

    private static final String ACCOUNT = "62626262-6262-6262-6262-626262626262";
    private static final String ACCOUNT_B = "63636363-6363-6363-6363-636363636363";
    private static final String IMAGE_1 = "data:image/png;base64,AAAA";

    @Autowired
    VideoCanvasWorkspaceRepository bindings;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM video_storyboard_workspace").then()
                .then(db.sql("DELETE FROM creation_draft").then())
                .then(db.sql("DELETE FROM video_storyboard").then())
                .block(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("TC-010：旧深链无匹配草稿 → 唯一补关联 + 服务端建最小视频草稿；不新建制作任务")
    void legacyDeepLinkCreatesSingleBindingAndMinimalDraft() {
        UUID storyboardId = seedStoryboard();

        Map<String, Object> first = bind(storyboardId, Map.of("operationId", "11111111-1111-4111-8111-111111111111"));
        assertThat(first.get("storyboardId")).isEqualTo(storyboardId.toString());
        assertThat(first.get("productionTaskId")).isNull();
        assertThat(((Number) first.get("editVersion")).longValue()).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        Map<String, Object> project = (Map<String, Object>) first.get("project");
        assertThat(project.get("capability")).isEqualTo("video");
        assertThat(project.get("id")).isNotNull();

        // 只有一条关联、一个草稿、零制作任务（整页刷新/深链打开不得新建任务）
        assertThat(countRows("video_storyboard_workspace")).isEqualTo(1L);
        assertThat(countRows("creation_draft")).isEqualTo(1L);
        assertThat(countRows("video_production_task")).isEqualTo(0L);

        // 最小草稿：inputs 只留 storyboard 引用；request_payload 的 base64 不得复制进 workspace
        // （jsonb 归一化带空格分隔，断言用裸 UUID 而非紧凑 JSON 形态）
        String workspaceJson = db.sql("SELECT workspace_json::text AS w FROM creation_draft "
                        + "WHERE id=CAST(:id AS uuid)").bind("id", project.get("id"))
                .map(row -> row.get("w", String.class)).one().block(Duration.ofSeconds(5));
        assertThat(workspaceJson).contains(storyboardId.toString()).contains("currentStep");
        assertThat(workspaceJson).doesNotContain(IMAGE_1).doesNotContain("data:");
    }

    @Test
    @DisplayName("TC-010：绑定响应丢失后原键重放 → 返回同一关联；两客户端不同键也拿到同一关联")
    void replayAndSecondClientReturnSameBinding() {
        UUID storyboardId = seedStoryboard();
        String opA = "22222222-2222-4222-8222-222222222222";
        String opB = "33333333-3333-4333-8333-333333333333";

        Map<String, Object> first = bind(storyboardId, Map.of("operationId", opA));
        Map<String, Object> replay = bind(storyboardId, Map.of("operationId", opA));
        Map<String, Object> secondClient = bind(storyboardId, Map.of("operationId", opB));

        String draftId = projectId(first);
        assertThat(projectId(replay)).isEqualTo(draftId);
        assertThat(projectId(secondClient)).isEqualTo(draftId);
        // 分镜唯一：三个请求、一条关联行；无副本草稿
        assertThat(countRows("video_storyboard_workspace")).isEqualTo(1L);
        assertThat(countRows("creation_draft")).isEqualTo(1L);
    }

    @Test
    @DisplayName("TC-010：分镜已有关联时换绑另一个草稿 → 409 不迁移；无关联但多候选 → 409 歧义")
    void reboundToDifferentDraftRejected() {
        // —— 无关联 + 两个引用该分镜的存量草稿 → 409 要求带 draft 进入 ——
        UUID ambiguous = seedStoryboard();
        seedVideoDraft(ambiguous, "草稿一", 1);
        seedVideoDraft(ambiguous, "草稿二", 1);
        bindExpect(ambiguous, Map.of("operationId", "55555555-5555-4555-8555-555555555555"), 409);
        assertThat(countRows("video_storyboard_workspace")).isEqualTo(0L);

        // —— 已有关联：不带 draftId 直接返回既有关联（不查候选）；换绑别的草稿 409；同草稿放行 ——
        UUID storyboardId = seedStoryboard();
        Map<String, Object> bound = bind(storyboardId, Map.of("operationId", "44444444-4444-4444-8444-444444444444"));
        String boundDraftId = projectId(bound);

        Map<String, Object> same = bind(storyboardId, Map.of("operationId", "77777777-7777-4777-8777-777777777777"));
        assertThat(projectId(same)).isEqualTo(boundDraftId);

        UUID otherDraft = seedVideoDraft(storyboardId, "其他草稿", 1);
        bindExpect(storyboardId, Map.of("operationId", "66666666-6666-4666-8666-666666666666",
                "draftId", otherDraft.toString(), "expectedDraftVersion", 1), 409);

        Map<String, Object> replay = bind(storyboardId, Map.of("operationId", "77777777-7777-4777-8777-777777777778",
                "draftId", boundDraftId, "expectedDraftVersion", 1));
        assertThat(projectId(replay)).isEqualTo(boundDraftId);
        assertThat(countRows("video_storyboard_workspace")).isEqualTo(1L);
    }

    @Test
    @DisplayName("TC-010：draftId 必须带 expectedDraftVersion；版本不符 409；非视频能力 400")
    void draftVersionCasAndCapabilityGuard() {
        UUID storyboardId = seedStoryboard();
        UUID draft = seedVideoDraft(storyboardId, "快速模式草稿", 3);

        bindExpect(storyboardId, Map.of("operationId", "88888888-8888-4888-8888-888888888888",
                "draftId", draft.toString()), 400);
        bindExpect(storyboardId, Map.of("operationId", "99999999-9999-4999-8999-999999999999",
                "draftId", draft.toString(), "expectedDraftVersion", 2), 409);

        // 单一存量候选（先清掉其他可能命中）→ 直接采纳，不建新草稿
        db.sql("DELETE FROM creation_draft WHERE id <> CAST(:id AS uuid)").bind("id", draft.toString()).then()
                .block(Duration.ofSeconds(5));
        Map<String, Object> adopted = bind(storyboardId, Map.of("operationId", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
        assertThat(projectId(adopted)).isEqualTo(draft.toString());
        assertThat(countRows("creation_draft")).isEqualTo(1L);

        // 非视频能力草稿显式关联 → 400
        UUID article = seedRawDraft(ACCOUNT, Map.of("schemaVersion", 1, "capability", "article"), 1);
        UUID freshStoryboard = seedStoryboard();
        bindExpect(freshStoryboard, Map.of("operationId", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "draftId", article.toString(), "expectedDraftVersion", 1), 400);
    }

    @Test
    @DisplayName("TC-010：跨账号分镜 404；关联草稿被删后 409 不静默换绑")
    void crossAccountAndDeletedDraftGuards() {
        UUID storyboardId = seedStoryboard();
        Map<String, Object> bound = bind(storyboardId, Map.of("operationId", "cccccccc-cccc-4ccc-8ccc-cccccccccccc"));
        String draftId = projectId(bound);

        // 跨账号：分镜不存在口径（404，防存在性探测）
        client().post().uri("/api/video-production/storyboards/{id}/workspace", storyboardId)
                .header("X-Grassland-Identity", sign(ACCOUNT_B, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("operationId", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
                .exchange().expectStatus().isNotFound();

        // 关联草稿软删后再绑定 → 409（资源不可用，不迁移）
        db.sql("UPDATE creation_draft SET deleted_at=now() WHERE id=CAST(:id AS uuid)")
                .bind("id", draftId).then().block(Duration.ofSeconds(5));
        bindExpect(storyboardId, Map.of("operationId", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"), 409);
    }

    // ---- 帮手 ----

    private Map<String, Object> bind(UUID storyboardId, Map<String, Object> body) {
        return bindExpect(storyboardId, body, 200);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bindExpect(UUID storyboardId, Map<String, Object> body, int status) {
        Object[] holder = new Object[1];
        client().post().uri("/api/video-production/storyboards/{id}/workspace", storyboardId)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isEqualTo(status)
                .expectBody(Map.class).consumeWith(result -> {
                    Map<String, Object> envelope = result.getResponseBody();
                    holder[0] = envelope == null ? null : envelope.get("data");
                });
        return (Map<String, Object>) holder[0];
    }

    @SuppressWarnings("unchecked")
    private static String projectId(Map<String, Object> binding) {
        return (String) ((Map<String, Object>) binding.get("project")).get("id");
    }

    private long countRows(String table) {
        return db.sql("SELECT COUNT(*) AS n FROM " + table).map(row -> row.get("n", Long.class))
                .one().block(Duration.ofSeconds(5));
    }

    private UUID seedStoryboard() {
        String payload = "{\"images\":[\"" + IMAGE_1 + "\"],\"shopName\":\"店\",\"platform\":\"douyin\"}";
        return UUID.fromString(db.sql("INSERT INTO video_storyboard(account_id, target_duration_seconds, "
                        + "request_payload) VALUES (:account, 25, CAST(:payload AS jsonb)) RETURNING id::text")
                .bind("account", ACCOUNT).bind("payload", payload)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }

    /** 快速模式同款草稿：workspace 引用该分镜（inputs.video.storyboardId 合法输入引用）。 */
    private UUID seedVideoDraft(UUID storyboardId, String title, int version) {
        Map<String, Object> workspace = Map.of("schemaVersion", 1, "capability", "video",
                "currentStep", "storyboard",
                "inputs", Map.of("video", Map.of("storyboardId", storyboardId.toString())));
        return seedRawDraft(ACCOUNT, workspace, title, version);
    }

    private UUID seedRawDraft(String account, Map<String, Object> workspace, int version) {
        return seedRawDraft(account, workspace, "文章草稿", version);
    }

    private UUID seedRawDraft(String account, Map<String, Object> workspace, String title, int version) {
        return UUID.fromString(db.sql("INSERT INTO creation_draft(id, owner_account_id, title, source_type, "
                        + "status, version, workspace_json) VALUES (gen_random_uuid(), :account, :title, "
                        + "'independent', 'draft', :version, CAST(:workspace AS jsonb)) RETURNING id::text")
                .bind("account", account).bind("title", title).bind("version", version)
                .bind("workspace", writeJson(workspace))
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private static String writeJson(Map<String, Object> value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
