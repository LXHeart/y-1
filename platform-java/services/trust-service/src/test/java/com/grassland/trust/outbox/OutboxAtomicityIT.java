package com.grassland.trust.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;

import com.grassland.trust.TrustItSupport;
import com.grassland.trust.event.EventEnvelope;
import com.grassland.trust.event.OutboxRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Mono;

/**
 * Slice 7C：证明 trust controller 写路径的「领域写 + outbox append」在同一 R2DBC 事务。
 *
 * <p>用 {@code @MockitoSpyBean} 把 {@link OutboxRepository#append} 针对某事件类型注入失败，
 * 断言领域写（争议 / 裁决状态迁移）随之回滚——而非「写了领域态却丢了事件」的静默缺口。
 * 覆盖 DisputeController 的 open/decide 两种写形态；AdjudicationController 的 assign/appeal/finalize
 * 同形态（均 {@code transactions.transactional(写+outbox)}），由既有 AdjudicationControllerIT 守 happy path。
 */
class OutboxAtomicityIT extends TrustItSupport {

    @MockitoSpyBean
    OutboxRepository outbox;

    @Test
    void openRollsBackDisputeWhenOutboxFails() {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String eng = UUID.randomUUID().toString();

        failOutboxOn("DisputeOpened");
        client().post().uri("/api/trust/disputes")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", eng, "reason", "未履约"))
                .exchange().expectStatus().is5xxServerError();

        assertThat(disputeCountByEngagement(eng)).isZero();   // 争议未建
    }

    @Test
    @SuppressWarnings("unchecked")
    void decideRollsBackWhenOutboxFails() {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String eng = UUID.randomUUID().toString();

        Map<String, Object> opened = client().post().uri("/api/trust/disputes")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", eng, "reason", "未履约"))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        String id = (String) ((Map<String, Object>) opened.get("data")).get("id");

        failOutboxOn("DisputeDecided");
        client().post().uri("/api/trust/disputes/" + id + "/decide")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("decision", "in_merchant_favor"))
                .exchange().expectStatus().is5xxServerError();

        assertThat(disputeStatus(id)).isEqualTo("open");   // 未迁移到 final
    }

    private void failOutboxOn(String eventType) {
        doReturn(Mono.<Void>error(new RuntimeException("outbox injected failure")))
                .when(outbox).append(argThat((EventEnvelope e) -> e != null && eventType.equals(e.eventType())));
    }

    private long disputeCountByEngagement(String eng) {
        Long c = db.sql("SELECT COUNT(*)::bigint AS c FROM dispute_case WHERE engagement_ref = :eng")
                .bind("eng", eng).map(row -> row.get("c", Long.class)).one().block();
        return c == null ? 0L : c;
    }

    private String disputeStatus(String id) {
        return db.sql("SELECT status FROM dispute_case WHERE id = CAST(:id AS uuid)")
                .bind("id", id).map(row -> row.get("status", String.class)).one().block();
    }
}
