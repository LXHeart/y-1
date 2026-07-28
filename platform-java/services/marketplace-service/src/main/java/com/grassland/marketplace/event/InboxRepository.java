package com.grassland.marketplace.event;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class InboxRepository {

    private final DatabaseClient db;

    public InboxRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Boolean> recordIfAbsent(
            String consumerName,
            ConsumerRecord<String, String> record,
            TrustEventEnvelope envelope,
            String payloadSha256) {
        return db.sql("""
                        INSERT INTO marketplace_inbox
                            (consumer_name, event_id, event_type, aggregate_type, aggregate_id,
                             payload_sha256, source_topic, source_partition, source_offset)
                        VALUES (:consumerName, :eventId, :eventType, :aggregateType, :aggregateId,
                                :payloadSha256, :topic, :partition, :offset)
                        ON CONFLICT DO NOTHING
                        RETURNING event_id
                        """)
                .bind("consumerName", consumerName)
                .bind("eventId", envelope.eventId())
                .bind("eventType", envelope.eventType())
                .bind("aggregateType", envelope.aggregateType())
                .bind("aggregateId", envelope.aggregateId())
                .bind("payloadSha256", payloadSha256)
                .bind("topic", record.topic())
                .bind("partition", record.partition())
                .bind("offset", record.offset())
                .map((row, metadata) -> true)
                .one()
                .switchIfEmpty(validateExisting(consumerName, envelope, payloadSha256));
    }

    private Mono<Boolean> validateExisting(
            String consumerName, TrustEventEnvelope envelope, String payloadSha256) {
        return db.sql("""
                        SELECT event_type, aggregate_type, aggregate_id, payload_sha256
                        FROM marketplace_inbox
                        WHERE consumer_name = :consumerName AND event_id = :eventId
                        """)
                .bind("consumerName", consumerName)
                .bind("eventId", envelope.eventId())
                .map(row -> new ExistingEvent(
                        row.get("event_type", String.class),
                        row.get("aggregate_type", String.class),
                        row.get("aggregate_id", String.class),
                        row.get("payload_sha256", String.class)))
                .one()
                .switchIfEmpty(Mono.error(new EventContractException(
                        "source offset already belongs to a different event")))
                .flatMap(existing -> existing.matches(envelope, payloadSha256)
                        ? Mono.just(false)
                        : Mono.error(new EventContractException(
                                "conflicting trust event content for eventId " + envelope.eventId())));
    }

    private record ExistingEvent(
            String eventType, String aggregateType, String aggregateId, String payloadSha256) {

        private boolean matches(TrustEventEnvelope envelope, String expectedPayloadSha256) {
            return java.util.Objects.equals(eventType, envelope.eventType())
                    && java.util.Objects.equals(aggregateType, envelope.aggregateType())
                    && java.util.Objects.equals(aggregateId, envelope.aggregateId())
                    && java.util.Objects.equals(payloadSha256, expectedPayloadSha256);
        }
    }
}
