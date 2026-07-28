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

        failOutboxOn("TaskPublished");
        publishFailing(merchant, org).expectStatus().is5xxServerError();

        assertThat(taskCountByOrg(org)).isZero();   // 任务未建
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
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("organizationId", org);
        b.put("title", "任务");
        Map<String, Object> resp = client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(b)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) resp.get("data")).get("id");
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
