package com.grassland.identity.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class OutboxRepository {
    private final DatabaseClient db;
    private final ObjectMapper mapper = new ObjectMapper();

    public OutboxRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Void> ensureTable() {
        return db.sql("""
            CREATE TABLE IF NOT EXISTS outbox (
                id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                event_id text NOT NULL UNIQUE,
                event_type text NOT NULL,
                aggregate_type text NOT NULL,
                aggregate_id text NOT NULL,
                payload json NOT NULL,
                created_at timestamptz NOT NULL DEFAULT now(),
                published_at timestamptz
            )
            """).then();
    }

    public Mono<Void> append(EventEnvelope event) {
        try {
            String payload = mapper.writeValueAsString(event.payload());
            return db.sql("""
                INSERT INTO outbox (event_id, event_type, aggregate_type, aggregate_id, payload)
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
            FROM outbox WHERE published_at IS NULL
            ORDER BY created_at LIMIT :limit
            """)
            .bind("limit", limit)
            .map((row) -> new OutboxRow(
                row.get("id", String.class),
                row.get("event_id", String.class),
                row.get("event_type", String.class),
                row.get("aggregate_type", String.class),
                row.get("aggregate_id", String.class),
                row.get("payload", String.class)
            ))
            .all();
    }

    public Mono<Void> markPublished(String id) {
        return db.sql("UPDATE outbox SET published_at = now() WHERE id = CAST(:id AS uuid)")
            .bind("id", id)
            .then();
    }

    public record OutboxRow(String id, String eventId, String eventType,
                            String aggregateType, String aggregateId, String payloadJson) {}
}
