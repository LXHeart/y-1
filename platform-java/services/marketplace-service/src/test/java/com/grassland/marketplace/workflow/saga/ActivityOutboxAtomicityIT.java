package com.grassland.marketplace.workflow.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Mono;

/**
 * Slice 7C-2：证明 marketplace 写活动（{@link ApplicationReservationActivityImpl}）的「领域写 + outbox append」
 * 在同一 R2DBC 事务。
 *
 * <p>与 controller 的 {@code OutboxAtomicityIT} 同思路，但**直接调 activity bean**（不经 HTTP / workflow）：
 * {@code @MockitoSpyBean} 把 {@link OutboxRepository#append} 针对目标事件类型注入失败，断言领域写（报名状态迁移）
 * 随之回滚——而非「写了领域态却丢了事件」。覆盖 beginAcceptance / activateEngagement / compensateAcceptance
 * 三个多写活动（reserveFunds 单写、captureSettlement 见 SettlementActivityImpl）。
 */
class ActivityOutboxAtomicityIT extends MarketplaceItSupport {

    @MockitoSpyBean
    OutboxRepository outbox;

    @Autowired
    ApplicationReservationActivityImpl reservationActivity;

    @Test
    void beginAcceptanceRollsBackWhenOutboxFails() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String taskId = publishTask(merchant, org);
        String appId = apply(recommender, taskId);   // pending

        failOutboxOn("ApplicationAcceptanceStarted");
        AcceptanceInput input = new AcceptanceInput(appId, taskId, merchant, org, 500L);
        assertThatThrownBy(() -> reservationActivity.beginAcceptance(input))
                .isInstanceOf(RuntimeException.class);
        assertThat(applicationStatus(appId)).isEqualTo("pending");   // pending→reserving 回滚
    }

    @Test
    void activateEngagementRollsBackWhenOutboxFails() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String taskId = publishTask(merchant, org);
        String appId = apply(recommender, taskId);
        setStatus(appId, "reserving");

        failOutboxOn("ApplicationAccepted");
        AcceptanceInput input = new AcceptanceInput(appId, taskId, merchant, org, 500L);
        assertThatThrownBy(() -> reservationActivity.activateEngagement(input))
                .isInstanceOf(RuntimeException.class);
        assertThat(applicationStatus(appId)).isEqualTo("reserving");   // reserving→accepted 回滚
    }

    @Test
    void compensateAcceptanceRollsBackWhenOutboxFails() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String taskId = publishTask(merchant, org);
        String appId = apply(recommender, taskId);
        setStatus(appId, "reserving");

        failOutboxOn("ApplicationReservationFailed");
        AcceptanceInput input = new AcceptanceInput(appId, taskId, merchant, org, 500L);
        // reserve=insufficientFunds → 跳过跨服务 finance.release，只测本地 revertReserving + outbox 原子性
        assertThatThrownBy(() -> reservationActivity.compensateAcceptance(
                input, ReserveResult.insufficientFunds(), "activate_failed"))
                .isInstanceOf(RuntimeException.class);
        assertThat(applicationStatus(appId)).isEqualTo("reserving");   // reserving→pending 回滚
    }

    private void failOutboxOn(String eventType) {
        doReturn(Mono.<Void>error(new RuntimeException("outbox injected failure")))
                .when(outbox).append(argThat((EventEnvelope e) -> e != null && eventType.equals(e.eventType())));
    }

    private void setStatus(String appId, String status) {
        db.sql("UPDATE task_application SET status = :status WHERE id = CAST(:a AS uuid)")
                .bind("status", status).bind("a", appId).then().block();
    }

    private String applicationStatus(String appId) {
        return db.sql("SELECT status FROM task_application WHERE id = CAST(:a AS uuid)")
                .bind("a", appId).map(row -> row.get("status", String.class)).one().block();
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
}
