package com.grassland.intelligence.creationassistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 创作草稿集成测试（草场 PRD §4.9.7 / Slice 15 Stage 1）。复用 {@link IntelligenceItSupport}
 *（testcontainers postgres + 真实断言签名）。草稿不依赖对象存储，CRUD 全可测。
 *
 * <p>锁定：创建（source 关联）、列表（仅自己）、详情（跨账号 404）、自动保存（落 version 快照 +
 * 乐观锁 409）、软删（删后不可见/不可下钻）、鉴权。
 */
class CreationDraftControllerIT extends IntelligenceItSupport {

    private String header() {
        return "X-Grassland-Identity";
    }

    @SuppressWarnings("unchecked")
    private String createDraft(String account, String sourceType, String title) {
        Map<String, Object> response = client().post().uri("/api/creation-drafts")
                .header(header(), sign(account, null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("sourceType", sourceType, "title", title, "topic", "测试主题"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return ((Map<String, Object>) response.get("data")).get("id").toString();
    }

    @Test
    void createDraftReturnsIdAndDefaults() {
        Map<String, Object> response = client().post().uri("/api/creation-drafts")
                .header(header(), sign("user-a", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("sourceType", "independent", "title", "我的第一篇", "topic", "小红书爆款"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();

        assertThat(response).containsEntry("success", true);
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertThat(data).containsEntry("sourceType", "independent");
        assertThat(data).containsEntry("status", "draft");
        assertThat(data).containsEntry("version", 1);
        assertThat(data).containsEntry("topic", "小红书爆款");
        assertThat(data).containsKey("id");
    }

    @Test
    void createRejectsInvalidSourceType() {
        client().post().uri("/api/creation-drafts")
                .header(header(), sign("user-a", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("sourceType", "bogus", "title", "x"))
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void createTaskDraftCarriesTaskReference() {
        Map<String, Object> response = client().post().uri("/api/creation-drafts")
                .header(header(), sign("user-task", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("sourceType", "task", "title", "任务草稿",
                        "taskId", "task-123", "taskVersion", 5, "platform", "xiaohongshu"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertThat(data).containsEntry("taskId", "task-123");
        assertThat(data).containsEntry("taskVersion", 5);
        assertThat(data).containsEntry("platform", "xiaohongshu");
    }

    @Test
    void listReturnsOnlyOwnDrafts() {
        createDraft("user-list", "independent", "草稿A");
        createDraft("user-list", "hot-topic", "草稿B");
        createDraft("user-other", "independent", "别人的");

        Map<String, Object> response = client().get().uri("/api/creation-drafts")
                .header(header(), sign("user-list", null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) ((Map<String, Object>) response.get("data")).get("items");
        assertThat(items).hasSize(2);
        assertThat(items).allSatisfy(item -> assertThat(item.get("title").toString()).startsWith("草稿"));
    }

    @Test
    void getReturns404ForOtherAccountDraft() {
        String draftId = createDraft("user-owner", "independent", "owner 的草稿");
        client().get().uri("/api/creation-drafts/" + draftId)
                .header(header(), sign("user-stranger", null))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void autosaveIncrementsVersionAndPersistsContent() {
        String draftId = createDraft("user-save", "independent", "自动保存测试");

        // 自动保存：写正文 + 乐观锁 expectedVersion=1
        client().put().uri("/api/creation-drafts/" + draftId)
                .header(header(), sign("user-save", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 1, "title", "自动保存测试",
                        "content", "这是正文内容", "status", "in_progress"))
                .exchange()
                .expectStatus().isOk();

        // 详情应反映 v2 + 正文
        Map<String, Object> detail = getDraft(draftId, "user-save");
        assertThat(detail).containsEntry("version", 2);
        assertThat(detail).containsEntry("content", "这是正文内容");
        assertThat(detail).containsEntry("status", "in_progress");
    }

    @Test
    void autosaveRejectsStaleExpectedVersion() {
        String draftId = createDraft("user-lock", "independent", "锁测试");
        // 错误的 expectedVersion=99 → 409
        client().put().uri("/api/creation-drafts/" + draftId)
                .header(header(), sign("user-lock", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 99, "title", "冲突", "content", "x"))
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void deleteSoftDeletesAndHidesFromList() {
        String draftId = createDraft("user-del", "independent", "待删");
        client().delete().uri("/api/creation-drafts/" + draftId)
                .header(header(), sign("user-del", null))
                .exchange()
                .expectStatus().isOk();

        // 列表不再含该草稿
        Map<String, Object> listResp = client().get().uri("/api/creation-drafts")
                .header(header(), sign("user-del", null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        List<?> items = (List<?>) ((Map<String, Object>) listResp.get("data")).get("items");
        assertThat(items).isEmpty();

        // 详情 404
        client().get().uri("/api/creation-drafts/" + draftId)
                .header(header(), sign("user-del", null))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void requiresAuthentication() {
        client().get().uri("/api/creation-drafts")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getDraft(String draftId, String account) {
        Map<String, Object> response = client().get().uri("/api/creation-drafts/" + draftId)
                .header(header(), sign(account, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (Map<String, Object>) response.get("data");
    }
}
