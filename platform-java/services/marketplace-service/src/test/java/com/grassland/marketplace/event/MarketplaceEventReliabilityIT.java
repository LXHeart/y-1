package com.grassland.marketplace.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
import com.grassland.marketplace.taskcatalog.TaskRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class MarketplaceEventReliabilityIT extends MarketplaceItSupport {

    private static final String CONSUMER_NAME = "marketplace-trust-consumer";
    private static final String CLAIM_TOKEN_A = "11111111-1111-1111-1111-111111111111";
    private static final String CLAIM_TOKEN_B = "22222222-2222-2222-2222-222222222222";
    private static final String PAYLOAD_SHA256 = "a".repeat(64);
    private static final String CONFLICTING_PAYLOAD_SHA256 = "b".repeat(64);

    @Autowired
    OutboxRepository outbox;

    @Autowired
    InboxRepository inbox;

    @Autowired
    TrustEventProcessor processor;

    @Autowired
    TaskApplicationRepository applications;

    @Autowired
    TaskRepository tasks;

    @Autowired
    TransactionalOperator transactions;

    @BeforeEach
    void cleanReliabilityTables() {
        db.sql("DELETE FROM marketplace_inbox WHERE event_id LIKE 'slice7a-test-%'").then().block();
        db.sql("""
                        DELETE FROM marketplace_outbox
                        WHERE event_id LIKE 'slice7a-test-%' OR aggregate_id LIKE 'slice7a-app-%'
                        """)
                .then()
                .block();
        db.sql("DELETE FROM settlement_reconciliation WHERE source_event_id LIKE 'slice7a-test-%'").then().block();
    }

    @Test
    void v7MigrationAddsOutboxReliabilityFieldsAndInboxUniqueness() throws Exception {
        Long reliabilityColumns = db.sql("""
                        SELECT count(*) AS c FROM information_schema.columns
                        WHERE table_name = 'marketplace_outbox'
                          AND column_name IN ('attempt_count', 'next_attempt_at', 'claim_token', 'claimed_until', 'last_error_code')
                        """)
                .map(row -> row.get("c", Long.class))
                .one()
                .block();

        assertThat(reliabilityColumns).isEqualTo(5L);

        ConsumerRecord<String, String> record = record("slice7a-test-unique", "slice7a-app-1");
        TrustEventEnvelope envelope = new TrustEventEnvelope(
                "slice7a-test-unique",
                "DisputeFinalized",
                "DisputeCase",
                "d-1",
                new ObjectMapper().readTree(record.value()).get("payload"));
        assertThat(inbox.recordIfAbsent(CONSUMER_NAME, record, envelope, PAYLOAD_SHA256).block())
                .isTrue();
        assertThat(inbox.recordIfAbsent(CONSUMER_NAME, record, envelope, PAYLOAD_SHA256).block())
                .isFalse();
        StepVerifier.create(inbox.recordIfAbsent(
                        CONSUMER_NAME, record, envelope, CONFLICTING_PAYLOAD_SHA256))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(EventContractException.class)
                        .hasMessageContaining("conflicting trust event content"))
                .verify();
    }

    @Test
    void claimUsesLeaseBackoffAndOwnerCheckedAcknowledgment() {
        outbox.append(new EventEnvelope(
                        "slice7a-test-claim",
                        "EngagementSettled",
                        "TaskApplication",
                        "slice7a-app-1",
                        1,
                        Instant.now(),
                        null,
                        Map.of("applicationId", "slice7a-app-1")))
                .block();

        OutboxRepository.OutboxRow first = outbox
                .claimBatch(CLAIM_TOKEN_A, 10, Duration.ofSeconds(30))
                .single()
                .block();
        assertThat(first).isNotNull();
        assertThat(first.attemptCount()).isEqualTo(1);
        assertThat(outbox.claimBatch(CLAIM_TOKEN_B, 10, Duration.ofSeconds(30)).collectList().block())
                .isEmpty();

        assertThat(outbox.markPublished(first.id(), CLAIM_TOKEN_B).block()).isFalse();
        assertThat(outbox.markFailed(
                        first.id(), CLAIM_TOKEN_A, "broker unavailable", Duration.ofSeconds(10))
                .block()).isTrue();
        assertThat(outbox.claimBatch(CLAIM_TOKEN_B, 10, Duration.ofSeconds(30)).collectList().block())
                .isEmpty();

        db.sql("""
                        UPDATE marketplace_outbox SET next_attempt_at = now() - interval '1 second'
                        WHERE event_id = 'slice7a-test-claim'
                        """)
                .then()
                .block();
        OutboxRepository.OutboxRow retried = outbox
                .claimBatch(CLAIM_TOKEN_B, 10, Duration.ofSeconds(30))
                .single()
                .block();
        assertThat(retried.attemptCount()).isEqualTo(2);
        assertThat(outbox.markPublished(retried.id(), CLAIM_TOKEN_A).block()).isFalse();
        assertThat(outbox.markPublished(retried.id(), CLAIM_TOKEN_B).block()).isTrue();

        Long published = db.sql("""
                        SELECT count(*) AS c FROM marketplace_outbox
                        WHERE event_id = 'slice7a-test-claim' AND published_at IS NOT NULL
                        """)
                .map(row -> row.get("c", Long.class))
                .one()
                .block();
        assertThat(published).isEqualTo(1L);
    }

    @Test
    void expiredLeaseCanBeReclaimedWithoutAllowingStaleAcknowledgment() {
        outbox.append(new EventEnvelope(
                        "slice7a-test-expired-lease",
                        "EngagementSettled",
                        "TaskApplication",
                        "slice7a-app-expired",
                        1,
                        Instant.now(),
                        null,
                        Map.of("applicationId", "slice7a-app-expired")))
                .block();

        OutboxRepository.OutboxRow first = outbox
                .claimBatch(CLAIM_TOKEN_A, 1, Duration.ofSeconds(30))
                .single()
                .block();
        db.sql("""
                        UPDATE marketplace_outbox SET claimed_until = now() - interval '1 second'
                        WHERE event_id = 'slice7a-test-expired-lease'
                        """)
                .then()
                .block();

        OutboxRepository.OutboxRow reclaimed = outbox
                .claimBatch(CLAIM_TOKEN_B, 1, Duration.ofSeconds(30))
                .single()
                .block();

        assertThat(reclaimed.id()).isEqualTo(first.id());
        assertThat(outbox.markPublished(first.id(), CLAIM_TOKEN_A).block()).isFalse();
        assertThat(outbox.markPublished(reclaimed.id(), CLAIM_TOKEN_B).block()).isTrue();
    }

    @Test
    void processorCommitsInboxAndReconciliationAtomicallyAndDeduplicates() {
        ConsumerRecord<String, String> record = record("slice7a-test-process", "slice7a-app-42");

        StepVerifier.create(processor.process(record))
                .expectNext(TrustEventProcessingResult.PROCESSED)
                .verifyComplete();
        StepVerifier.create(processor.process(record))
                .expectNext(TrustEventProcessingResult.DUPLICATE)
                .verifyComplete();

        assertThat(count("marketplace_inbox", "event_id", "slice7a-test-process")).isEqualTo(1L);
        assertThat(count("settlement_reconciliation", "source_event_id", "slice7a-test-process")).isEqualTo(1L);
        // Slice 7B：消费侧不再写 EngagementSettled——由对账 workflow 确认资金后才写。
        assertThat(outboxSettledCount("slice7a-app-42")).isZero();
    }

    @Test
    void processorRollsBackInboxWhenReconciliationEnqueueFails() {
        com.grassland.marketplace.settlement.SettlementReconciliationRepository failingReconciliations =
                new com.grassland.marketplace.settlement.SettlementReconciliationRepository(db) {
                    @Override
                    public Mono<Boolean> enqueue(
                            String sourceEventId, String disputeId, String applicationId,
                            String organizationId, String finalDecision, String workflowId) {
                        return Mono.error(new IllegalStateException("reconciliation unavailable"));
                    }
                };
        TrustEventProcessor failingProcessor = new TrustEventProcessor(
                inbox, failingReconciliations, transactions, new ObjectMapper(), CONSUMER_NAME,
                applications, tasks, outbox);
        ConsumerRecord<String, String> record = record("slice7a-test-rollback", "slice7a-app-99");

        StepVerifier.create(failingProcessor.process(record))
                .expectErrorMessage("reconciliation unavailable")
                .verify();

        assertThat(count("marketplace_inbox", "event_id", "slice7a-test-rollback")).isZero();
        assertThat(count("settlement_reconciliation", "source_event_id", "slice7a-test-rollback")).isZero();
    }

    private long outboxSettledCount(String applicationId) {
        return db.sql("SELECT count(*) AS c FROM marketplace_outbox"
                        + " WHERE event_type = 'EngagementSettled' AND aggregate_id = :appId")
                .bind("appId", applicationId)
                .map(row -> row.get("c", Long.class))
                .one()
                .block();
    }

    private long count(String table, String column, String value) {
        return db.sql("SELECT count(*) AS c FROM " + table + " WHERE " + column + " = :value")
                .bind("value", value)
                .map(row -> row.get("c", Long.class))
                .one()
                .block();
    }

    private ConsumerRecord<String, String> record(String eventId, String applicationId) {
        String json = """
                {"eventId":"%s","eventType":"DisputeFinalized","aggregateType":"DisputeCase","aggregateId":"d-1",
                 "payload":{"disputeId":"d-1","engagementRef":"%s","finalDecision":"for_recommender"}}
                """.formatted(eventId, applicationId);
        return new ConsumerRecord<>("grassland.trust.events", 2, 17L, "d-1", json);
    }
}
