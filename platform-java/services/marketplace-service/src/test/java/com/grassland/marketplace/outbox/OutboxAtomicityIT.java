package com.grassland.marketplace.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * Slice 7C：证明 marketplace controller 写路径的「领域写 + outbox append」在同一 R2DBC 事务。
 *
 * <p>用 {@code @MockitoSpyBean} 把 {@link OutboxRepository#append} 针对某事件类型注入失败，
 * 断言领域写（任务 / 报名 / 状态迁移）随之回滚——而非「写了领域态却丢了事件」的静默缺口。
 */
class OutboxAtomicityIT extends MarketplaceItSupport {

    @MockitoSpyBean
    OutboxRepository outbox;

    @Test
    void publishRollsBackTaskWhenOutboxFails() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();

        failOutboxOn("TaskSubmittedForReview");
        publishFailing(merchant, org).expectStatus().is5xxServerError();

        assertThat(taskCountByOrg(org)).isZero();   // 任务未建
    }

    @Test
    void approveRollsBackPublicationWhenOutboxFails() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> task = submitTask(merchant, org, 4);
        String taskId = (String) task.get("id");
        int version = ((Number) task.get("version")).intValue();

        failOutboxOn("TaskPublished");
        client().post().uri("/api/admin/tasks/" + taskId + "/review/approve")
                .header("X-Grassland-Identity", signWithRole(
                        UUID.randomUUID().toString(), "platform_admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", version))
                .exchange().expectStatus().is5xxServerError();

        assertThat(taskStatus(taskId)).isEqualTo("pending_review");
        assertThat(taskVersion(taskId)).isEqualTo(1);
        assertThat(taskPublishedAtIsNull(taskId)).isTrue();
        assertThat(taskMinimumRecommenderLevel(taskId)).isEqualTo(4);
        assertThat(taskVersionCount(taskId)).isZero();
        assertThat(taskMinimumLevelSnapshot(taskId)).isNull();
        // The submitted audit row belongs to the earlier successful submit transaction;
        // only the approved row must roll back with TaskPublished.
        assertThat(taskReviewCount(taskId)).isEqualTo(1);
    }

    @Test
    void submitRollsBackApplicationWhenOutboxFails() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String taskId = publishTask(merchant, org);   // outbox 正常
        assertThat(taskCountByOrg(org)).isEqualTo(1);

        failOutboxOn("ApplicationSubmitted");
        client().post().uri("/api/tasks/" + taskId + "/applications")
                .header("X-Grassland-Identity", sign(recommender, "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("note", "申请"))
                .exchange().expectStatus().is5xxServerError();

        assertThat(applicationCountByTask(taskId)).isZero();   // 报名未建
    }

    @Test
    void acceptDirectRollsBackWhenOutboxFails() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String taskId = publishTask(merchant, org);   // 非资金型（无 bounty）
        String appId = apply(recommender, taskId);

        failOutboxOn("ApplicationAccepted");
        client().post().uri("/api/tasks/" + taskId + "/applications/" + appId + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().is5xxServerError();

        assertThat(applicationStatus(appId)).isEqualTo("pending");   // 未迁移到 accepted
    }

    @Test
    void withdrawRollsBackWhenOutboxFails() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String taskId = publishTask(merchant, org);
        String appId = apply(recommender, taskId);

        failOutboxOn("ApplicationWithdrawn");
        client().post().uri("/api/tasks/" + taskId + "/applications/" + appId + "/withdraw")
                .header("X-Grassland-Identity", sign(recommender, "recommender"))
                .exchange().expectStatus().is5xxServerError();

        assertThat(applicationStatus(appId)).isEqualTo("pending");   // 未撤销
    }

    // ---------- GL-P1-TASK-001 Stage 1：生命周期写 + outbox 同事务 ----------

    @Test
    void publishDraftRollsBackWhenOutboxFails() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String taskId = createDraft(merchant, org);

        failOutboxOn("TaskSubmittedForReview");
        client().post().uri("/api/tasks/" + taskId + "/publish")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion", 0))
                .exchange().expectStatus().is5xxServerError();

        // 回滚：仍 draft / version 0，且未落快照。
        assertThat(taskStatus(taskId)).isEqualTo("draft");
        assertThat(taskVersionCount(taskId)).isZero();
    }

    @Test
    void closeRollsBackWhenOutboxFails() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String taskId = publishTask(merchant, org);

        failOutboxOn("TaskClosed");
        client().post().uri("/api/tasks/" + taskId + "/close")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion", 2))
                .exchange().expectStatus().is5xxServerError();

        assertThat(taskStatus(taskId)).isEqualTo("published");   // 未迁移到 closed
    }

    @Test
    void cancelRollsBackWhenOutboxFails() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String taskId = publishTask(merchant, org);

        failOutboxOn("TaskCancelled");
        client().post().uri("/api/tasks/" + taskId + "/cancel")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion", 2))
                .exchange().expectStatus().is5xxServerError();

        assertThat(taskStatus(taskId)).isEqualTo("published");   // 未迁移到 cancelled
    }

    @SuppressWarnings("unchecked")
    private String createDraft(String merchant, String org) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("organizationId", org);
        b.put("title", "草稿");
        Map<String, Object> resp = client().post().uri("/api/tasks/draft")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(b)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) resp.get("data")).get("id");
    }

    private String taskStatus(String taskId) {
        return db.sql("SELECT status FROM task WHERE id = CAST(:t AS uuid)")
                .bind("t", taskId).map(row -> row.get("status", String.class)).one().block();
    }

    private long taskVersionCount(String taskId) {
        Long c = db.sql("SELECT COUNT(*)::bigint AS c FROM task_version WHERE task_id = CAST(:t AS uuid)")
                .bind("t", taskId).map(row -> row.get("c", Long.class)).one().block();
        return c == null ? 0L : c;
    }

    private int taskVersion(String taskId) {
        Integer version = db.sql("SELECT version FROM task WHERE id = CAST(:t AS uuid)")
                .bind("t", taskId).map(row -> row.get("version", Integer.class)).one().block();
        return version == null ? -1 : version;
    }

    private boolean taskPublishedAtIsNull(String taskId) {
        Boolean publishedAtIsNull = db.sql(
                        "SELECT published_at IS NULL AS is_null FROM task WHERE id = CAST(:t AS uuid)")
                .bind("t", taskId)
                .map(row -> row.get("is_null", Boolean.class)).one().block();
        return Boolean.TRUE.equals(publishedAtIsNull);
    }

    private int taskMinimumRecommenderLevel(String taskId) {
        Integer level = db.sql("SELECT min_recommender_level FROM task WHERE id = CAST(:t AS uuid)")
                .bind("t", taskId).map(row -> row.get("min_recommender_level", Integer.class)).one().block();
        return level == null ? -1 : level;
    }

    private Integer taskMinimumLevelSnapshot(String taskId) {
        return db.sql("SELECT min_recommender_level FROM task_version WHERE task_id = CAST(:t AS uuid)")
                .bind("t", taskId).map(row -> row.get("min_recommender_level", Integer.class)).one().block();
    }

    private long taskReviewCount(String taskId) {
        Long count = db.sql("SELECT COUNT(*)::bigint AS c FROM task_review WHERE task_id = CAST(:t AS uuid)")
                .bind("t", taskId).map(row -> row.get("c", Long.class)).one().block();
        return count == null ? 0L : count;
    }

    private void failOutboxOn(String eventType) {
        doReturn(Mono.<Void>error(new RuntimeException("outbox injected failure")))
                .when(outbox).append(argThat((EventEnvelope e) -> e != null && eventType.equals(e.eventType())));
    }

    @SuppressWarnings("unchecked")
    private WebTestClient.ResponseSpec publishFailing(String merchant, String org) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("organizationId", org);
        b.put("title", "任务");
        return client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(b)
                .exchange();
    }

    @SuppressWarnings("unchecked")
    private String publishTask(String merchant, String org) {
        Map<String, Object> task = submitTask(merchant, org);
        String taskId = (String) task.get("id");
        int version = ((Number) task.get("version")).intValue();
        client().post().uri("/api/admin/tasks/" + taskId + "/review/approve")
                .header("X-Grassland-Identity", signWithRole(
                        UUID.randomUUID().toString(), "platform_admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", version))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("published");
        return taskId;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> submitTask(String merchant, String org) {
        return submitTask(merchant, org, 1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> submitTask(String merchant, String org, int minimumRecommenderLevel) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("organizationId", org);
        b.put("title", "任务");
        b.put("minRecommenderLevel", minimumRecommenderLevel);
        Map<String, Object> resp = client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(b)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (Map<String, Object>) resp.get("data");
    }

    @SuppressWarnings("unchecked")
    private String apply(String recommender, String taskId) {
        Map<String, Object> resp = client().post().uri("/api/tasks/" + taskId + "/applications")
                .header("X-Grassland-Identity", sign(recommender, "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("note", "申请"))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) resp.get("data")).get("id");
    }

    private long taskCountByOrg(String org) {
        Long c = db.sql("SELECT COUNT(*)::bigint AS c FROM task WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", org).map(row -> row.get("c", Long.class)).one().block();
        return c == null ? 0L : c;
    }

    private long applicationCountByTask(String taskId) {
        Long c = db.sql("SELECT COUNT(*)::bigint AS c FROM task_application WHERE task_id = CAST(:t AS uuid)")
                .bind("t", taskId).map(row -> row.get("c", Long.class)).one().block();
        return c == null ? 0L : c;
    }

    private String applicationStatus(String appId) {
        return db.sql("SELECT status FROM task_application WHERE id = CAST(:a AS uuid)")
                .bind("a", appId).map(row -> row.get("status", String.class)).one().block();
    }
}
