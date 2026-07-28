package com.grassland.marketplace.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.grassland.marketplace.MarketplaceItSupport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.context.TestConfiguration;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Slice 7A real-broker gate for marketplace trust consumption and outbox publication. */
@Import(MarketplaceKafkaTestcontainersIT.FaultInjectionConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MarketplaceKafkaTestcontainersIT extends MarketplaceItSupport {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final String RUN_ID = UUID.randomUUID().toString().replace("-", "");
    private static final String SOURCE_TOPIC = "marketplace-trust-it-" + RUN_ID;
    private static final String DLT_TOPIC = SOURCE_TOPIC + ".DLT";
    private static final String OUTBOX_TOPIC = "marketplace-outbox-it-" + RUN_ID;
    private static final String GROUP_ID = "marketplace-trust-it-" + RUN_ID;
    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.1"));

    static {
        KAFKA.start();
        try (AdminClient admin = adminClient()) {
            admin.createTopics(List.of(
                            new org.apache.kafka.clients.admin.NewTopic(SOURCE_TOPIC, 1, (short) 1),
                            new org.apache.kafka.clients.admin.NewTopic(DLT_TOPIC, 1, (short) 1),
                            new org.apache.kafka.clients.admin.NewTopic(OUTBOX_TOPIC, 1, (short) 1)))
                    .all()
                    .get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("failed to create Kafka integration topics", e);
        }
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private FaultInjectingSettlementReconciliationRepository faultReconciliations;

    private AdminClient admin;

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.enable-auto-commit", () -> "false");
        registry.add("spring.kafka.listener.ack-mode", () -> "manual");
        registry.add("spring.kafka.listener.async-acks", () -> "true");
        registry.add("spring.kafka.producer.properties.acks", () -> "all");
        registry.add("spring.kafka.producer.properties.delivery.timeout.ms", () -> "5000");
        registry.add("spring.kafka.producer.properties.request.timeout.ms", () -> "2000");
        registry.add("marketplace.trust-consumer.enabled", () -> "true");
        registry.add("marketplace.trust-consumer.topic", () -> SOURCE_TOPIC);
        registry.add("marketplace.trust-consumer.group-id", () -> GROUP_ID);
        registry.add("marketplace.outbox.topic", () -> OUTBOX_TOPIC);
        registry.add("marketplace.outbox.enabled", () -> "false");
    }

    @BeforeAll
    void openAdminClient() {
        admin = adminClient();
    }

    @AfterAll
    void closeAdminClient() {
        if (admin != null) {
            admin.close(Duration.ofSeconds(5));
        }
    }

    @BeforeEach
    void resetState() {
        faultReconciliations.resetFaults();
        db.sql("DELETE FROM marketplace_inbox").then().block(AWAIT_TIMEOUT);
        db.sql("DELETE FROM marketplace_outbox").then().block(AWAIT_TIMEOUT);
        db.sql("DELETE FROM settlement_reconciliation").then().block(AWAIT_TIMEOUT);
    }

    @Test
    @Order(1)
    void duplicateEventCreatesOneInboxAndOneReconciliationRequest() throws Exception {
        String sourceEventId = "duplicate-" + RUN_ID;
        String value = envelope(sourceEventId, "app-duplicate", "for_recommender");

        send(SOURCE_TOPIC, "dispute-duplicate", value);
        send(SOURCE_TOPIC, "dispute-duplicate", value);

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            assertThat(inboxCount(sourceEventId)).isEqualTo(1);
            assertThat(reconciliationCount("app-duplicate")).isEqualTo(1);
        });
    }

    @Test
    @Order(2)
    void malformedPoisonGoesToDltWithOriginAndLaterValidEventProcesses() throws Exception {
        String poisonKey = "poison-" + RUN_ID;
        String poisonValue = "not-json{";
        ProducerRecord<String, String> poison = new ProducerRecord<>(SOURCE_TOPIC, poisonKey, poisonValue);
        poison.headers().add("origin-service", "trust-service".getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(poison).get(10, TimeUnit.SECONDS);

        String validEventId = "after-poison-" + RUN_ID;
        send(SOURCE_TOPIC, "dispute-after-poison", envelope(validEventId, "app-after-poison", "for_merchant"));

        ConsumerRecord<String, String> dlt = consumeByKey(DLT_TOPIC, poisonKey, Duration.ofSeconds(20));
        assertThat(dlt).isNotNull();
        assertThat(dlt.key()).isEqualTo(poisonKey);
        assertThat(dlt.value()).isEqualTo(poisonValue);
        assertThat(headerValue(dlt, "origin-service")).isEqualTo("trust-service");
        assertThat(headerValue(dlt, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(SOURCE_TOPIC);
        assertThat(dlt.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION)).isNotNull();
        assertThat(dlt.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET)).isNotNull();

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            assertThat(inboxCount(validEventId)).isEqualTo(1);
            assertThat(reconciliationCount("app-after-poison")).isEqualTo(1);
        });
    }

    @Test
    @Order(3)
    void transientDatabaseFailureRetriesTwiceThenSucceedsWithoutDlt() throws Exception {
        String eventId = "retry-" + RUN_ID;
        String key = "dispute-retry";
        faultReconciliations.failNextEnqueues(2);

        send(SOURCE_TOPIC, key, envelope(eventId, "app-retry", "for_recommender"));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            assertThat(faultReconciliations.enqueueAttempts()).isEqualTo(3);
            assertThat(inboxCount(eventId)).isEqualTo(1);
            assertThat(reconciliationCount("app-retry")).isEqualTo(1);
        });
        assertThat(consumeByKey(DLT_TOPIC, key, Duration.ofSeconds(2))).isNull();
    }

    @Test
    @Order(4)
    void asyncMonoDoesNotCommitKafkaOffsetBeforeDatabaseTransactionCompletes() throws Exception {
        String eventId = "async-" + RUN_ID;
        faultReconciliations.holdNextTransactionOpen();

        var metadata = kafkaTemplate
                .send(SOURCE_TOPIC, "dispute-async", envelope(eventId, "app-async", "for_merchant"))
                .get(10, TimeUnit.SECONDS)
                .getRecordMetadata();
        TopicPartition partition = new TopicPartition(SOURCE_TOPIC, metadata.partition());

        assertThat(faultReconciliations.awaitTransactionOpen(Duration.ofSeconds(10))).isTrue();
        assertThat(inboxCount(eventId)).isZero();
        assertThat(reconciliationCount("app-async")).isZero();
        assertThat(committedOffset(partition)).isLessThanOrEqualTo(metadata.offset());

        faultReconciliations.releaseTransaction();

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            assertThat(inboxCount(eventId)).isEqualTo(1);
            assertThat(reconciliationCount("app-async")).isEqualTo(1);
            assertThat(committedOffset(partition)).isGreaterThanOrEqualTo(metadata.offset() + 1);
        });
    }

    @Test
    @Order(5)
    void publisherMarksOutboxOnlyAfterRealBrokerAck() throws Exception {
        EventEnvelope event = event("publisher-ack-" + RUN_ID, "app-publisher-ack");
        outboxRepository.append(event).block(AWAIT_TIMEOUT);
        db.sql("""
                        UPDATE marketplace_outbox SET next_attempt_at = now() - interval '1 second'
                        WHERE event_id = :eventId
                        """)
                .bind("eventId", event.eventId())
                .then()
                .block(AWAIT_TIMEOUT);

        MarketplaceOutboxProperties ackProperties = new MarketplaceOutboxProperties(
                true, OUTBOX_TOPIC, 50, 8, Duration.ofSeconds(10), Duration.ofMinutes(5),
                Duration.ofSeconds(1), Duration.ofMinutes(1));
        OutboxPublisher ackPublisher = new OutboxPublisher(
                outboxRepository,
                kafkaTemplate,
                ackProperties,
                new OutboxPublisherMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                new com.fasterxml.jackson.databind.ObjectMapper());
        ackPublisher.publishBatch().block(AWAIT_TIMEOUT);

        assertThat(isPublished(event.eventId())).isTrue();
        ConsumerRecord<String, String> published =
                consumeByKey(OUTBOX_TOPIC, "app-publisher-ack", Duration.ofSeconds(10));
        assertThat(published).isNotNull();
        assertThat(published.value()).contains(event.eventId(), "EngagementSettled");
        assertThat(isPublished(event.eventId())).isTrue();
    }

    @Test
    @Order(6)
    void publisherLeavesOutboxUnpublishedWhenBrokerCannotAck() throws Exception {
        EventEnvelope event = event("publisher-failure-" + RUN_ID, "app-publisher-failure");
        outboxRepository.append(event).block(AWAIT_TIMEOUT);
        OutboxRepository.OutboxRow row = unpublished(event.eventId());
        db.sql("""
                        UPDATE marketplace_outbox
                        SET claim_token = NULL, claimed_until = NULL, next_attempt_at = now() - interval '1 second'
                        WHERE event_id = :eventId
                        """)
                .bind("eventId", event.eventId())
                .then()
                .block(AWAIT_TIMEOUT);
        DefaultKafkaProducerFactory<String, String> producerFactory = failingProducerFactory();
        KafkaTemplate<String, String> failingTemplate = new KafkaTemplate<>(producerFactory);
        MarketplaceOutboxProperties failingProperties = new MarketplaceOutboxProperties(
                true, OUTBOX_TOPIC, 1, 1, Duration.ofSeconds(10), Duration.ofMinutes(5),
                Duration.ofSeconds(1), Duration.ofMinutes(1));
        OutboxPublisher failingPublisher = new OutboxPublisher(
                outboxRepository,
                failingTemplate,
                failingProperties,
                new OutboxPublisherMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                new com.fasterxml.jackson.databind.ObjectMapper());

        try {
            failingPublisher.publishBatch().block(Duration.ofSeconds(8));
            assertThat(isPublished(event.eventId())).isFalse();
            Integer attempts = db.sql("""
                            SELECT attempt_count FROM marketplace_outbox WHERE event_id = :eventId
                            """)
                    .bind("eventId", event.eventId())
                    .map(result -> result.get("attempt_count", Integer.class))
                    .one()
                    .block(AWAIT_TIMEOUT);
            assertThat(attempts).isEqualTo(row.attemptCount() + 1);
        } finally {
            producerFactory.destroy();
        }
    }

    private void send(String topic, String key, String value) throws Exception {
        kafkaTemplate.send(topic, key, value).get(10, TimeUnit.SECONDS);
    }

    private long inboxCount(String eventId) {
        Long count = db.sql("SELECT count(*) AS count FROM marketplace_inbox WHERE event_id = :eventId")
                .bind("eventId", eventId)
                .map(row -> row.get("count", Long.class))
                .one()
                .block(AWAIT_TIMEOUT);
        return count == null ? 0 : count;
    }

    private long outboxCount(String aggregateId, String eventType) {
        Long count = db.sql("""
                        SELECT count(*) AS count FROM marketplace_outbox
                        WHERE aggregate_id = :aggregateId AND event_type = :eventType
                        """)
                .bind("aggregateId", aggregateId)
                .bind("eventType", eventType)
                .map(row -> row.get("count", Long.class))
                .one()
                .block(AWAIT_TIMEOUT);
        return count == null ? 0 : count;
    }

    private long reconciliationCount(String applicationId) {
        Long count = db.sql("""
                        SELECT count(*) AS count FROM settlement_reconciliation
                        WHERE application_id = :applicationId
                        """)
                .bind("applicationId", applicationId)
                .map(row -> row.get("count", Long.class))
                .one()
                .block(AWAIT_TIMEOUT);
        return count == null ? 0 : count;
    }

    private boolean isPublished(String eventId) {
        Boolean published = db.sql("""
                        SELECT published_at IS NOT NULL AS published
                        FROM marketplace_outbox WHERE event_id = :eventId
                        """)
                .bind("eventId", eventId)
                .map(row -> row.get("published", Boolean.class))
                .one()
                .block(AWAIT_TIMEOUT);
        return Boolean.TRUE.equals(published);
    }

    private OutboxRepository.OutboxRow unpublished(String eventId) {
        String claimToken = UUID.randomUUID().toString();
        return outboxRepository.claimBatch(claimToken, 100, Duration.ofSeconds(30))
                .filter(row -> eventId.equals(row.eventId()))
                .single()
                .block(AWAIT_TIMEOUT);
    }

    private long committedOffset(TopicPartition partition) throws Exception {
        Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(GROUP_ID)
                .partitionsToOffsetAndMetadata()
                .get(10, TimeUnit.SECONDS);
        OffsetAndMetadata offset = offsets.get(partition);
        return offset == null ? 0 : offset.offset();
    }

    private static AdminClient adminClient() {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
    }

    private static ConsumerRecord<String, String> consumeByKey(String topic, String key, Duration timeout) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "probe-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Instant deadline = Instant.now().plus(timeout);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(topic));
            while (Instant.now().isBefore(deadline)) {
                Duration remaining = Duration.between(Instant.now(), deadline);
                ConsumerRecords<String, String> records = consumer.poll(remaining.compareTo(Duration.ofMillis(500)) > 0
                        ? Duration.ofMillis(500) : remaining);
                for (ConsumerRecord<String, String> record : records) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
            return null;
        }
    }

    private static String headerValue(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static DefaultKafkaProducerFactory<String, String> failingProducerFactory() {
        Map<String, Object> properties = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.RETRIES_CONFIG, 0,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 500,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 500,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 1000);
        return new DefaultKafkaProducerFactory<>(properties);
    }

    private static EventEnvelope event(String eventId, String applicationId) {
        return new EventEnvelope(eventId, "EngagementSettled", "TaskApplication", applicationId,
                1, Instant.now(), null, Map.of("applicationId", applicationId, "reason", "test"));
    }

    private static String envelope(String eventId, String applicationId, String decision) {
        return """
                {"eventId":"%s","eventType":"DisputeFinalized","aggregateType":"DisputeCase",
                 "aggregateId":"dispute-1","payload":{"disputeId":"dispute-1","engagementRef":"%s","finalDecision":"%s"}}
                """.formatted(eventId, applicationId, decision);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FaultInjectionConfig {

        @Bean
        @Primary
        FaultInjectingSettlementReconciliationRepository faultInjectingSettlementReconciliationRepository(
                DatabaseClient databaseClient) {
            return new FaultInjectingSettlementReconciliationRepository(databaseClient);
        }
    }

    /** processor 现消费侧调 reconciliation.enqueue（不再是 outbox.append），故故障注入挂在 reconciliation 仓库。 */
    static final class FaultInjectingSettlementReconciliationRepository
            extends com.grassland.marketplace.settlement.SettlementReconciliationRepository {

        private final AtomicInteger remainingFailures = new AtomicInteger();
        private final AtomicInteger attempts = new AtomicInteger();
        private volatile CountDownLatch transactionOpen = new CountDownLatch(0);
        private volatile Sinks.One<Void> transactionGate;

        FaultInjectingSettlementReconciliationRepository(DatabaseClient databaseClient) {
            super(databaseClient);
        }

        @Override
        public Mono<Boolean> enqueue(
                String sourceEventId, String disputeId, String applicationId,
                String organizationId, String finalDecision, String workflowId) {
            attempts.incrementAndGet();
            if (remainingFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                return Mono.error(new TransientDataAccessResourceException("injected transient DB failure"));
            }
            Mono<Boolean> enqueue = super.enqueue(sourceEventId, disputeId, applicationId,
                    organizationId, finalDecision, workflowId);
            Sinks.One<Void> gate = transactionGate;
            if (gate == null) {
                return enqueue;
            }
            return enqueue.then(Mono.defer(() -> {
                transactionOpen.countDown();
                return gate.asMono();
            })).thenReturn(true);
        }

        void failNextEnqueues(int count) {
            remainingFailures.set(count);
        }

        int enqueueAttempts() {
            return attempts.get();
        }

        void holdNextTransactionOpen() {
            transactionOpen = new CountDownLatch(1);
            transactionGate = Sinks.one();
        }

        boolean awaitTransactionOpen(Duration timeout) throws InterruptedException {
            return transactionOpen.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        void releaseTransaction() {
            Sinks.One<Void> gate = transactionGate;
            if (gate != null) {
                gate.tryEmitEmpty();
            }
        }

        void resetFaults() {
            releaseTransaction();
            remainingFailures.set(0);
            attempts.set(0);
            transactionOpen = new CountDownLatch(0);
            transactionGate = null;
        }
    }
}
