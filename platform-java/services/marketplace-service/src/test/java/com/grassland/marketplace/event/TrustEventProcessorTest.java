package com.grassland.marketplace.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.marketplace.settlement.SettlementReconciliationRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class TrustEventProcessorTest {

    private final InboxRepository inbox = mock(InboxRepository.class);
    private final SettlementReconciliationRepository reconciliations = mock(SettlementReconciliationRepository.class);
    private final TransactionalOperator transactions = mock(TransactionalOperator.class);
    private final TrustEventProcessor processor;

    TrustEventProcessorTest() {
        when(transactions.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));
        processor = new TrustEventProcessor(
                inbox, reconciliations, transactions, new ObjectMapper(), "marketplace-trust-consumer");
    }

    @Test
    void validDisputeFinalizedEnqueuesReconciliationWithoutSettledOutbox() {
        ConsumerRecord<String, String> record = record(envelope("event-1", "DisputeFinalized", "app-42", "for_recommender"));
        when(inbox.recordIfAbsent(
                        eq("marketplace-trust-consumer"),
                        eq(record),
                        any(TrustEventEnvelope.class),
                        anyString()))
                .thenReturn(Mono.just(true));
        when(reconciliations.enqueue(
                        eq("event-1"), eq("d-1"), eq("app-42"), any(), eq("for_recommender"),
                        eq("settlement-reconcile-d-1")))
                .thenReturn(Mono.just(true));

        StepVerifier.create(processor.process(record))
                .expectNext(TrustEventProcessingResult.PROCESSED)
                .verifyComplete();

        // 不再在消费侧写 EngagementSettled——由对账 workflow 确认资金后才写。
        verify(reconciliations).enqueue(
                eq("event-1"), eq("d-1"), eq("app-42"), any(), eq("for_recommender"),
                eq("settlement-reconcile-d-1"));
    }

    @Test
    void duplicateInboxRecordDoesNotEnqueue() {
        ConsumerRecord<String, String> record = record(envelope("event-1", "DisputeFinalized", "app-42", "for_merchant"));
        when(inbox.recordIfAbsent(
                        eq("marketplace-trust-consumer"),
                        eq(record),
                        any(TrustEventEnvelope.class),
                        anyString()))
                .thenReturn(Mono.just(false));

        StepVerifier.create(processor.process(record))
                .expectNext(TrustEventProcessingResult.DUPLICATE)
                .verifyComplete();

        verify(reconciliations, never()).enqueue(any(), any(), any(), any(), any(), any());
    }

    @Test
    void unrelatedValidTrustEventIsIgnoredWithoutInboxWrite() {
        ConsumerRecord<String, String> record = record(envelope("event-2", "DisputeOpened", "app-42", null));

        StepVerifier.create(processor.process(record))
                .expectNext(TrustEventProcessingResult.IGNORED)
                .verifyComplete();

        verify(inbox, never()).recordIfAbsent(any(), any(), any(), any());
        verify(reconciliations, never()).enqueue(any(), any(), any(), any(), any(), any());
    }

    @Test
    void nullRecordIsAContractError() {
        StepVerifier.create(processor.process(null))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(EventContractException.class)
                        .hasMessageContaining("valid JSON"))
                .verify();
    }

    @Test
    void emptyRecordValueIsAContractError() {
        StepVerifier.create(processor.process(new ConsumerRecord<>(
                        "grassland.trust.events", 0, 0L, "d-1", "")))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(EventContractException.class)
                        .hasMessageContaining("valid JSON"))
                .verify();
    }

    @Test
    void malformedJsonIsANonRetryableContractError() {
        StepVerifier.create(processor.process(record("not-json{")))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(EventContractException.class)
                        .hasMessageContaining("valid JSON"))
                .verify();
    }

    @Test
    void missingEventIdIsAContractError() {
        String json = """
                {"eventType":"DisputeFinalized","aggregateType":"DisputeCase","aggregateId":"d-1",
                 "payload":{"disputeId":"d-1","engagementRef":"app-42","finalDecision":"for_merchant"}}
                """;

        StepVerifier.create(processor.process(record(json)))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(EventContractException.class)
                        .hasMessageContaining("eventId"))
                .verify();
    }

    @Test
    void missingEngagementReferenceIsAContractError() {
        String json = envelope("event-3", "DisputeFinalized", "", "for_merchant");

        StepVerifier.create(processor.process(record(json)))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(EventContractException.class)
                        .hasMessageContaining("engagementRef"))
                .verify();
    }

    @Test
    void missingDisputeIdIsAContractError() {
        // Slice 7B：disputeId 必填（对账 workflow id 派生自它）
        String json = """
                {"eventId":"event-x","eventType":"DisputeFinalized","aggregateType":"DisputeCase","aggregateId":"d-1",
                 "payload":{"engagementRef":"app-42","finalDecision":"for_merchant"}}
                """;

        StepVerifier.create(processor.process(record(json)))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(EventContractException.class)
                        .hasMessageContaining("disputeId"))
                .verify();
    }

    @Test
    void missingFinalDecisionIsAContractError() {
        String json = envelope("event-4", "DisputeFinalized", "app-42", null);

        StepVerifier.create(processor.process(record(json)))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(EventContractException.class)
                        .hasMessageContaining("finalDecision"))
                .verify();
    }

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("grassland.trust.events", 2, 17L, "d-1", value);
    }

    private String envelope(String eventId, String eventType, String engagementRef, String decision) {
        String payload = "{\"disputeId\":\"d-1\",\"engagementRef\":\"" + engagementRef + "\""
                + (decision == null ? "" : ",\"finalDecision\":\"" + decision + "\"") + "}";
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + eventType
                + "\",\"aggregateType\":\"DisputeCase\",\"aggregateId\":\"d-1\",\"payload\":" + payload + "}";
    }
}
