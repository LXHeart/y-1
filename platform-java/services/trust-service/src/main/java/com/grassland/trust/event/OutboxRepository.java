package com.grassland.trust.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * trust outbox 写入（草场 Epic 6 Slice 6A，复刻 finance 精简版）。表由 Flyway V1 建；Kafka 发布器留后续。
 * payload 用本地 {@link ObjectMapper} 序列化为 JSON。
 */
@Component
public class OutboxRepository {

    private final DatabaseClient db;
    private final ObjectMapper mapper = new ObjectMapper();

    public OutboxRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Void> append(EventEnvelope event) {
        try {
            String payload = mapper.writeValueAsString(event.payload());
            return db.sql("""
                    INSERT INTO trust_outbox (event_id, event_type, aggregate_type, aggregate_id, payload)
                    VALUES (:eventId, :eventType, :aggType, :aggId, CAST(:payload AS json))
                    ON CONFLICT (event_id) DO NOTHING
                    """)
                    .bind("eventId", event.eventId())
                    .bind("eventType", event.eventType())
                    .bind("aggType", event.aggregateType())
                    .bind("aggId", event.aggregateId())
                    .bind("payload", payload)
                    .then();
        } catch (Exception e) {
            return Mono.error(e);
        }
    }
}
