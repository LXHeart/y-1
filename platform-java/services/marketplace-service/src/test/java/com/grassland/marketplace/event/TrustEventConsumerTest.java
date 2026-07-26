package com.grassland.marketplace.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

/**
 * {@link TrustEventConsumer} 单元测试（草场 Epic 6 Slice 6C Phase E，Mockito）。直接调 {@code handle}（绕过 Kafka）：
 * DisputeFinalized → 补 EngagementSettled（aggregate_id=engagementRef）；幂等（trust eventId 决定派生 event_id）；
 * 非 DisputeFinalized / 不可解析 → 忽略。
 */
class TrustEventConsumerTest {

    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final TrustEventConsumer consumer = new TrustEventConsumer(outbox);

    @Test
    void disputeFinalizedAppendsEngagementSettled() {
        when(outbox.append(any())).thenReturn(Mono.empty());
        consumer.handle(envelope("ev-1", "DisputeFinalized", "app-42", "for_recommender"));

        ArgumentCaptor<EventEnvelope> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(outbox).append(captor.capture());
        EventEnvelope e = captor.getValue();
        assertThat(e.eventType()).isEqualTo("EngagementSettled");
        assertThat(e.aggregateId()).isEqualTo("app-42");
        assertThat(e.payload().get("reason")).isEqualTo("adjudication:for_recommender");
    }

    @Test
    void derivedEventIdIsDeterministicForIdempotency() {
        when(outbox.append(any())).thenReturn(Mono.empty());
        // Kafka 至少一次重投同一 trust 事件 → 消费方两次 append，派生 event_id 相同（outbox ON CONFLICT 去重）
        consumer.handle(envelope("ev-1", "DisputeFinalized", "app-42", "for_merchant"));
        consumer.handle(envelope("ev-1", "DisputeFinalized", "app-42", "for_merchant"));

        ArgumentCaptor<EventEnvelope> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(outbox, times(2)).append(captor.capture());
        String first = captor.getAllValues().get(0).eventId();
        assertThat(captor.getAllValues().get(1).eventId()).isEqualTo(first);  // 确定性 → DB 去重生效
        assertThat(first).isEqualTo(UUID.nameUUIDFromBytes("SettlementResolved:ev-1".getBytes()).toString());
    }

    @Test
    void ignoresNonFinalizedEvents() {
        consumer.handle(envelope("ev-2", "DisputeDecided", "app-42", "for_merchant"));
        consumer.handle(envelope("ev-3", "DisputeOpened", "app-42", null));
        verify(outbox, never()).append(any());
    }

    @Test
    void ignoresUnparseableJson() {
        consumer.handle("not-json{");
        verify(outbox, never()).append(any());
    }

    /** 构造与 OutboxPublisher.buildEnvelope 同形的 JSON 信封。 */
    private String envelope(String eventId, String eventType, String engagementRef, String decision) {
        String payload = "{\"disputeId\":\"d-1\",\"engagementRef\":\"" + engagementRef + "\""
                + (decision == null ? "" : ",\"finalDecision\":\"" + decision + "\"") + "}";
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + eventType
                + "\",\"aggregateType\":\"DisputeCase\",\"aggregateId\":\"d-1\",\"payload\":" + payload + "}";
    }
}
