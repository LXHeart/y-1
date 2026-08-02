package com.grassland.marketplace.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 死信登记 + 重投/弃置（GL-P1-OPS-001 Stage 2）。
 *
 * <p>登记走 {@link OpsDltRegistrar}（消费者的纯逻辑部分，不需要真 Kafka）；重投 mock KafkaTemplate。
 */
class OpsDltControllerIT extends MarketplaceItSupport {

    private static final String OPS_A = "11111111-1111-4111-8111-111111111111";
    private static final String OPS_B = "22222222-2222-4222-8222-222222222222";
    private static final String DLT_TOPIC = "grassland.trust.events.DLT";
    private static final String ORIGINAL_TOPIC = "grassland.trust.events";

    @MockitoBean
    private KafkaTemplate<Object, Object> kafka;

    @Autowired
    private OpsDltRegistrar registrar;

    @Autowired
    private OpsCaseRepository cases;

    @Autowired
    private OpsDltMessageRepository messages;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM ops_case_action").fetch().rowsUpdated().block();
        db.sql("DELETE FROM ops_case_audit").fetch().rowsUpdated().block();
        db.sql("DELETE FROM ops_case").fetch().rowsUpdated().block();
        db.sql("DELETE FROM ops_dlt_message").fetch().rowsUpdated().block();
    }

    private OpsDltMessage register(long offset) {
        return registrar.register(DLT_TOPIC, 0, offset, ORIGINAL_TOPIC, "key-" + offset,
                "{\"eventType\":\"DisputeFinalized\"}", "NullPointerException").block();
    }

    /** 登记 + 走完双人审批，返回消息。 */
    private OpsDltMessage approvedMessage(long offset) {
        OpsDltMessage message = register(offset);
        OpsCase c = cases.findBySource(OpsCaseSource.DLT_MESSAGE, message.position()).block();
        cases.submit(c.id(), 1L, OPS_A, null).block();
        cases.decide(c.id(), 2L, OPS_B, true, null).block();
        return message;
    }

    @SuppressWarnings("unchecked")
    private void kafkaSendSucceeds() {
        when(kafka.send(any(String.class), any(), any()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<Object, Object>) null));
    }

    @Test
    @DisplayName("登记死信同时开处置单（同事务），队列可见")
    void registerOpensCase() {
        OpsDltMessage message = register(10L);
        assertThat(message.status()).isEqualTo("pending");
        assertThat(message.originalTopic()).isEqualTo(ORIGINAL_TOPIC);
        assertThat(message.errorSummary()).isEqualTo("NullPointerException");

        OpsCase c = cases.findBySource(OpsCaseSource.DLT_MESSAGE, message.position()).block();
        assertThat(c).isNotNull();
        assertThat(c.reason()).isEqualTo(ORIGINAL_TOPIC);
        assertThat(c.severity()).isEqualTo("normal");

        client().get().uri("/api/ops/dlt")
                .header("X-Grassland-Identity", signWithRole(OPS_A, "customer_service"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].topic").isEqualTo(DLT_TOPIC)
                .jsonPath("$.data[0].offset").isEqualTo(10)
                .jsonPath("$.data[0].status").isEqualTo("pending");
    }

    @Test
    @DisplayName("同一位点重复登记幂等：不开第二张单，也不写第二条 registered")
    void registerIsIdempotent() {
        OpsDltMessage first = register(11L);
        OpsDltMessage replay = register(11L);
        assertThat(replay.id()).isEqualTo(first.id());

        assertThat(count("ops_dlt_message")).isEqualTo(1L);
        assertThat(count("ops_case")).isEqualTo(1L);
        assertThat(count("ops_case_audit")).isEqualTo(1L);
    }

    @Test
    @DisplayName("商家身份读死信队列 → 403")
    void queueRequiresOpsRole() {
        client().get().uri("/api/ops/dlt")
                .header("X-Grassland-Identity", sign("33333333-3333-4333-8333-333333333333", "merchant"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    @DisplayName("未审批不能重投 → 409，且不发 Kafka")
    void replayRequiresApproval() {
        OpsDltMessage message = register(12L);

        client().post().uri("/api/ops/dlt/" + message.id() + "/actions")
                .header("X-Grassland-Identity", signWithRole(OPS_A, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"replay\":true,\"operationId\":\"op-dlt-1\"}")
                .exchange().expectStatus().isEqualTo(409);

        verify(kafka, never()).send(any(String.class), any(), any());
        assertThat(messages.findById(message.id()).block().status()).isEqualTo("pending");
    }

    @Test
    @DisplayName("重投：发回原 topic 保留原 key，消息转 replayed，写 action_executed")
    void replaySendsToOriginalTopic() {
        kafkaSendSucceeds();
        OpsDltMessage message = approvedMessage(13L);

        client().post().uri("/api/ops/dlt/" + message.id() + "/actions")
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"replay\":true,\"operationId\":\"op-dlt-replay\"}")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.action").isEqualTo("dlt_replay")
                .jsonPath("$.data.status").isEqualTo("succeeded");

        verify(kafka, times(1)).send(ORIGINAL_TOPIC, "key-13", "{\"eventType\":\"DisputeFinalized\"}");

        OpsDltMessage after = messages.findById(message.id()).block();
        assertThat(after.status()).isEqualTo("replayed");
        assertThat(after.replayedAt()).isNotNull();
    }

    @Test
    @DisplayName("幂等：同一 operationId 重投两次，Kafka 只发一次")
    void replayIsIdempotent() {
        kafkaSendSucceeds();
        OpsDltMessage message = approvedMessage(14L);
        String body = "{\"replay\":true,\"operationId\":\"op-dlt-same\"}";

        client().post().uri("/api/ops/dlt/" + message.id() + "/actions")
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk();

        client().post().uri("/api/ops/dlt/" + message.id() + "/actions")
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("succeeded");

        verify(kafka, times(1)).send(any(String.class), any(), any());
        assertThat(count("ops_case_action")).isEqualTo(1L);
    }

    @Test
    @DisplayName("已处置的死信换新 operationId 也不能再重投 → 409")
    void alreadyHandledIsRejected() {
        kafkaSendSucceeds();
        OpsDltMessage message = approvedMessage(15L);

        client().post().uri("/api/ops/dlt/" + message.id() + "/actions")
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"replay\":true,\"operationId\":\"op-first\"}")
                .exchange().expectStatus().isOk();

        client().post().uri("/api/ops/dlt/" + message.id() + "/actions")
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"replay\":true,\"operationId\":\"op-second\"}")
                .exchange().expectStatus().isEqualTo(409);

        verify(kafka, times(1)).send(any(String.class), any(), any());
    }

    @Test
    @DisplayName("弃置只标记，不发 Kafka，消息行与审计都保留")
    void discardMarksOnly() {
        OpsDltMessage message = approvedMessage(16L);

        client().post().uri("/api/ops/dlt/" + message.id() + "/actions")
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"replay\":false,\"operationId\":\"op-discard\"}")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.action").isEqualTo("dlt_discard")
                .jsonPath("$.data.status").isEqualTo("succeeded")
                .jsonPath("$.data.outcome").isEqualTo("discarded");

        verify(kafka, never()).send(any(String.class), any(), any());
        OpsDltMessage after = messages.findById(message.id()).block();
        assertThat(after.status()).isEqualTo("discarded");
        assertThat(after.discardedAt()).isNotNull();
        assertThat(after.payload()).isNotBlank();  // 弃置不删 payload：死信是审计对象
    }

    @Test
    @DisplayName("Kafka 发送失败 → 台账 failed，消息留 pending 可再试")
    void replayFailureKeepsPending() {
        when(kafka.send(any(String.class), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        OpsDltMessage message = approvedMessage(17L);

        client().post().uri("/api/ops/dlt/" + message.id() + "/actions")
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"replay\":true,\"operationId\":\"op-dlt-fail\"}")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("failed");

        assertThat(messages.findById(message.id()).block().status()).isEqualTo("pending");
    }

    @Test
    @DisplayName("replay 缺失 → 400；不存在的消息 → 404")
    void validation() {
        OpsDltMessage message = approvedMessage(18L);

        client().post().uri("/api/ops/dlt/" + message.id() + "/actions")
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"operationId\":\"op-x\"}")
                .exchange().expectStatus().isEqualTo(400);

        client().post().uri("/api/ops/dlt/" + UUID.randomUUID() + "/actions")
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"replay\":true,\"operationId\":\"op-y\"}")
                .exchange().expectStatus().isNotFound();
    }

    private Long count(String table) {
        return db.sql("SELECT count(*) AS n FROM " + table)
                .map(r -> r.get("n", Long.class)).one().block();
    }
}
