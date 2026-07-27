package com.grassland.intelligence.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * intelligence outbox 写入（复刻 marketplace 精简版）。表由 Flyway V1 建。
 * payload 用本地 {@link ObjectMapper} 序列化为 JSON（Boot 4 的 Jackson autoconfig 在独立模块）。
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
                    INSERT INTO intelligence_outbox (event_id, event_type, aggregate_type, aggregate_id, payload)
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

    public Flux<OutboxRow> findUnpublished(int limit) {
        return db.sql("""
                SELECT id::text, event_id, event_type, aggregate_type, aggregate_id, payload::text
                FROM intelligence_outbox WHERE published_at IS NULL
                ORDER BY id LIMIT :limit
                """)
                .bind("limit", limit)
                .map(r -> new OutboxRow(
                        r.get("id", String.class), r.get("event_id", String.class),
                        r.get("event_type", String.class), r.get("aggregate_type", String.class),
                        r.get("aggregate_id", String.class), r.get("payload", String.class)))
                .all();
    }

    public Mono<Void> markPublished(String id) {
        // id 是 uuid（建表 default gen_random_uuid）——CAST 必须是 uuid，否则「发了但标不上」(marketplace 踩过的坑)。
        return db.sql("UPDATE intelligence_outbox SET published_at = now() WHERE id = CAST(:id AS uuid)")
                .bind("id", id).then();
    }

    public record OutboxRow(String id, String eventId, String eventType,
                            String aggregateType, String aggregateId, String payloadJson) {}
}
