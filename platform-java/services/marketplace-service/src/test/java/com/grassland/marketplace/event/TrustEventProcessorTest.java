package com.grassland.marketplace.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.marketplace.settlement.SettlementReconciliationRepository;
import com.grassland.marketplace.taskcatalog.Task;
import com.grassland.marketplace.taskcatalog.TaskApplication;
import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
import com.grassland.marketplace.taskcatalog.TaskRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SuppressWarnings("unchecked")
class TrustEventProcessorTest {

    private final InboxRepository inbox = mock(InboxRepository.class);
    private final SettlementReconciliationRepository reconciliations = mock(SettlementReconciliationRepository.class);
    private final TaskApplicationRepository applications = mock(TaskApplicationRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final TransactionalOperator transactions = mock(TransactionalOperator.class);
    private final TrustEventProcessor processor;

    TrustEventProcessorTest() {
        when(transactions.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));
        processor = new TrustEventProcessor(
                inbox, reconciliations, transactions, new ObjectMapper(), "marketplace-trust-consumer",
                applications, tasks, outbox);
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
    void deferredMerchantCaseFinalizationIsInboxProcessedWithoutReconciliation() {
        ConsumerRecord<String, String> record = record(deferredFinalizedEnvelope("event-deferred"));
        stubInboxInserted(record, true);

        StepVerifier.create(processor.process(record))
                .expectNext(TrustEventProcessingResult.SUPPRESSED)
                .verifyComplete();

        verify(reconciliations, never()).enqueue(any(), any(), any(), any(), any(), any());
    }

    @Test
    void duplicateDeferredFinalizationRemainsDuplicateWithoutReconciliation() {
        ConsumerRecord<String, String> record = record(deferredFinalizedEnvelope("event-deferred-duplicate"));
        stubInboxInserted(record, false);

        StepVerifier.create(processor.process(record))
                .expectNext(TrustEventProcessingResult.DUPLICATE)
                .verifyComplete();

        verify(reconciliations, never()).enqueue(any(), any(), any(), any(), any(), any());
    }

    @Test
    void deferredFlagMustBeBooleanAndNameItsSuccessor() {
        String nonBoolean = """
                {"eventId":"event-bad-flag","eventType":"DisputeFinalized","aggregateType":"DisputeCase","aggregateId":"d-1",
                 "payload":{"disputeId":"d-1","engagementRef":"app-42","finalDecision":"for_recommender",
                            "settlementDeferred":"true","successorDisputeId":"d-2"}}
                """;
        String missingSuccessor = """
                {"eventId":"event-no-successor","eventType":"DisputeFinalized","aggregateType":"DisputeCase","aggregateId":"d-1",
                 "payload":{"disputeId":"d-1","engagementRef":"app-42","finalDecision":"for_recommender",
                            "settlementDeferred":true}}
                """;

        StepVerifier.create(processor.process(record(nonBoolean)))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(EventContractException.class)
                        .hasMessageContaining("settlementDeferred"))
                .verify();
        StepVerifier.create(processor.process(record(missingSuccessor)))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(EventContractException.class)
                        .hasMessageContaining("successorDisputeId"))
                .verify();

        verify(inbox, never()).recordIfAbsent(any(), any(), any(), any());
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
        // DisputeOpened 现已被处理（见下）；此处用 DisputeDecided 验证「未处理类型」仍 IGNORED。
        ConsumerRecord<String, String> record = record(envelope("event-2", "DisputeDecided", "app-42", null));

        StepVerifier.create(processor.process(record))
                .expectNext(TrustEventProcessingResult.IGNORED)
                .verifyComplete();

        verify(inbox, never()).recordIfAbsent(any(), any(), any(), any());
        verify(reconciliations, never()).enqueue(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(applications, tasks, outbox);
    }

    // ---------- 争议对方通知：DisputeOpened → 解析对方 → 发 EngagementDisputed ----------

    @Test
    void disputeOpenedByMerchantNotifiesRecommenderAsCounterparty() {
        ConsumerRecord<String, String> record =
                openedRecord("event-open-1", "app-42", "owner-1", "merchant");
        stubInboxInserted(record, true);
        when(applications.findById("app-42")).thenReturn(Mono.just(application("app-42", "task-7", "rec-9")));
        when(tasks.findById("task-7")).thenReturn(Mono.just(task("task-7", "owner-1")));
        when(outbox.append(any(EventEnvelope.class))).thenReturn(Mono.empty());

        StepVerifier.create(processor.process(record))
                .expectNext(TrustEventProcessingResult.PROCESSED)
                .verifyComplete();

        EventEnvelope emitted = captureEmitted();
        assertThat(emitted.eventType()).isEqualTo("EngagementDisputed");
        assertThat(emitted.aggregateType()).isEqualTo("DisputeCase");
        assertThat(emitted.aggregateId()).isEqualTo("d-1");
        assertThat(emitted.payload())
                .containsEntry("counterpartyAccountId", "rec-9")
                .containsEntry("openedByRole", "merchant")
                .containsEntry("openedByAccountId", "owner-1")
                .containsEntry("disputeId", "d-1")
                .containsEntry("engagementRef", "app-42")
                .containsEntry("status", "open");
        // 确定性 eventId（派生自信源 eventId）→ outbox ON CONFLICT 与下游 inbox 幂等
        assertThat(emitted.eventId()).isEqualTo(UUID.nameUUIDFromBytes(
                "EngagementDisputed:event-open-1".getBytes(StandardCharsets.UTF_8)).toString());
    }

    @Test
    void disputeOpenedByRecommenderNotifiesTaskOwnerAsCounterparty() {
        ConsumerRecord<String, String> record =
                openedRecord("event-open-2", "app-42", "rec-9", "recommender");
        stubInboxInserted(record, true);
        when(applications.findById("app-42")).thenReturn(Mono.just(application("app-42", "task-7", "rec-9")));
        when(tasks.findById("task-7")).thenReturn(Mono.just(task("task-7", "owner-1")));
        when(outbox.append(any(EventEnvelope.class))).thenReturn(Mono.empty());

        StepVerifier.create(processor.process(record))
                .expectNext(TrustEventProcessingResult.PROCESSED)
                .verifyComplete();

        assertThat(captureEmitted().payload()).containsEntry("counterpartyAccountId", "owner-1");
    }

    /**
     * F7：merchant_rejection 争议的通知已由 contest 在同事务发的 MerchantContested 承担（收件人含推荐官）。
     * 此处若再派生 EngagementDisputed，推荐官会收到第二条通用争议通知 → 必须抑制，且不查库、不写 outbox。
     */
    @Test
    void merchantRejectionDisputeIsSuppressedInsteadOfDerivingDuplicateNotification() {
        ConsumerRecord<String, String> record =
                openedRecord("event-open-mr", "app-42", "owner-1", "merchant", "merchant_rejection");
        stubInboxInserted(record, true);

        StepVerifier.create(processor.process(record))
                .expectNext(TrustEventProcessingResult.SUPPRESSED)
                .verifyComplete();

        verify(applications, never()).findById(anyString());
        verify(tasks, never()).findById(anyString());
        verify(outbox, never()).append(any(EventEnvelope.class));
    }

    @Test
    void standardKindStillNotifiesCounterparty() {
        ConsumerRecord<String, String> record =
                openedRecord("event-open-std", "app-42", "owner-1", "merchant", "standard");
        stubInboxInserted(record, true);
        when(applications.findById("app-42")).thenReturn(Mono.just(application("app-42", "task-7", "rec-9")));
        when(tasks.findById("task-7")).thenReturn(Mono.just(task("task-7", "owner-1")));
        when(outbox.append(any(EventEnvelope.class))).thenReturn(Mono.empty());

        StepVerifier.create(processor.process(record))
                .expectNext(TrustEventProcessingResult.PROCESSED)
                .verifyComplete();

        assertThat(captureEmitted().payload()).containsEntry("counterpartyAccountId", "rec-9");
    }

    /** trust V5 之前在途的旧事件无 kind —— 必须当普通争议照常通知，不能判契约错误进 DLT。 */
    @Test
    void disputeOpenedWithoutKindFieldIsTreatedAsStandardAndStillNotifies() {
        ConsumerRecord<String, String> record =
                openedRecord("event-open-legacy", "app-42", "owner-1", "merchant");
        stubInboxInserted(record, true);
        when(applications.findById("app-42")).thenReturn(Mono.just(application("app-42", "task-7", "rec-9")));
        when(tasks.findById("task-7")).thenReturn(Mono.just(task("task-7", "owner-1")));
        when(outbox.append(any(EventEnvelope.class))).thenReturn(Mono.empty());

        StepVerifier.create(processor.process(record))
                .expectNext(TrustEventProcessingResult.PROCESSED)
                .verifyComplete();

        assertThat(captureEmitted().payload()).containsEntry("counterpartyAccountId", "rec-9");
    }

    @Test
    void disputeOpenedDuplicateDoesNotResolveOrAppendOutbox() {
        ConsumerRecord<String, String> record =
                openedRecord("event-open-3", "app-42", "owner-1", "merchant");
        stubInboxInserted(record, false);

        StepVerifier.create(processor.process(record))
                .expectNext(TrustEventProcessingResult.DUPLICATE)
                .verifyComplete();

        verify(outbox, never()).append(any());
        verifyNoInteractions(applications, tasks);
    }

    @Test
    void disputeOpenedWithMissingApplicationYieldsNoRecipient() {
        ConsumerRecord<String, String> record =
                openedRecord("event-open-4", "stale-app", "owner-1", "merchant");
        stubInboxInserted(record, true);
        when(applications.findById("stale-app")).thenReturn(Mono.empty());

        StepVerifier.create(processor.process(record))
                .expectNext(TrustEventProcessingResult.NO_RECIPIENT)
                .verifyComplete();

        verify(outbox, never()).append(any());
    }

    @Test
    void disputeOpenedWithoutValidOpenerRoleIsAContractErrorBeforeInboxWrite() {
        for (String role : new String[] {"", "judge", "MERCHANT"}) {
            ConsumerRecord<String, String> record = openedRecord("event-invalid-role-" + role, "app-42", "owner-1", role);

            StepVerifier.create(processor.process(record))
                    .expectErrorSatisfies(error -> assertThat(error)
                            .isInstanceOf(EventContractException.class)
                            .hasMessageContaining("openedByRole"))
                    .verify();
        }

        verify(inbox, never()).recordIfAbsent(any(), any(), any(), any());
        verifyNoInteractions(applications, tasks, outbox);
    }

    @Test
    void disputeOpenedWithMissingTaskYieldsNoRecipient() {
        ConsumerRecord<String, String> record =
                openedRecord("event-open-5", "app-42", "owner-1", "merchant");
        stubInboxInserted(record, true);
        when(applications.findById("app-42")).thenReturn(Mono.just(application("app-42", "task-7", "rec-9")));
        when(tasks.findById("task-7")).thenReturn(Mono.empty());

        StepVerifier.create(processor.process(record))
                .expectNext(TrustEventProcessingResult.NO_RECIPIENT)
                .verifyComplete();

        verify(outbox, never()).append(any());
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

    // ---- helpers ----

    private void stubInboxInserted(ConsumerRecord<String, String> record, boolean inserted) {
        when(inbox.recordIfAbsent(
                        eq("marketplace-trust-consumer"), eq(record),
                        any(TrustEventEnvelope.class), anyString()))
                .thenReturn(Mono.just(inserted));
    }

    private EventEnvelope captureEmitted() {
        ArgumentCaptor<EventEnvelope> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(outbox).append(captor.capture());
        return captor.getValue();
    }

    private static TaskApplication application(String id, String taskId, String recommenderAccountId) {
        return new TaskApplication(id, taskId, recommenderAccountId, "accepted", null, null, null, null, null, null, 0L, null, null);
    }

    private static Task task(String id, String ownerAccountId) {
        return new Task(id, ownerAccountId, null, "营销任务", null, "published", null, null, null, null, null, null, 1, null, null, null);
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

    private String deferredFinalizedEnvelope(String eventId) {
        return """
                {"eventId":"%s","eventType":"DisputeFinalized","aggregateType":"DisputeCase","aggregateId":"d-1",
                 "payload":{"disputeId":"d-1","engagementRef":"app-42","organizationId":"org-1",
                            "finalDecision":"for_recommender","settlementDeferred":true,"successorDisputeId":"d-2"}}
                """.formatted(eventId);
    }

    /** DisputeOpened 记录：disputeId/engagementRef 必填，openedByAccountId/openedByRole 携带对方解析所需。 */
    private ConsumerRecord<String, String> openedRecord(
            String eventId, String engagementRef, String openedByAccountId, String openedByRole) {
        return openedRecord(eventId, engagementRef, openedByAccountId, openedByRole, null);
    }

    /** kind == null 模拟 trust V5 之前的旧事件（载荷无该字段）。 */
    private ConsumerRecord<String, String> openedRecord(
            String eventId, String engagementRef, String openedByAccountId, String openedByRole, String kind) {
        String payload = "{\"disputeId\":\"d-1\",\"engagementRef\":\"" + engagementRef + "\""
                + ",\"organizationId\":\"org-1\""
                + ",\"openedByAccountId\":\"" + openedByAccountId + "\""
                + ",\"openedByRole\":\"" + openedByRole + "\""
                + (kind == null ? "" : ",\"kind\":\"" + kind + "\"")
                + ",\"status\":\"open\"}";
        String json = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"DisputeOpened\""
                + ",\"aggregateType\":\"DisputeCase\",\"aggregateId\":\"d-1\",\"payload\":" + payload + "}";
        return new ConsumerRecord<>("grassland.trust.events", 3, 31L, "d-1", json);
    }
}
