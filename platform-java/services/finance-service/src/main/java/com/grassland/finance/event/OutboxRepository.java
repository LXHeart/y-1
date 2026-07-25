package com.grassland.finance.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * finance outbox 写入（复刻 marketplace 精简版）。表由 Flyway V1 建；本 slice 仅 append，Kafka 发布器留后续。
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
                    INSERT INTO finance_outbox (event_id, event_type, aggregate_type, aggregate_id, payload)
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

    /** 未发布的 outbox 行（published_at IS NULL），按 created_at 升序取 limit 条。OutboxPublisher 轮询用。 */
    public reactor.core.publisher.Flux<OutboxRow> findUnpublished(int limit) {
        return db.sql("""
                SELECT id::text, event_id, event_type, aggregate_type, aggregate_id, payload::text
                FROM finance_outbox WHERE published_at IS NULL
                ORDER BY created_at LIMIT :limit
                """)
                .bind("limit", limit)
                .map(r -> new OutboxRow(
                        r.get("id", String.class), r.get("event_id", String.class),
                        r.get("event_type", String.class), r.get("aggregate_type", String.class),
                        r.get("aggregate_id", String.class), r.get("payload", String.class)))
                .all();
    }

    /** 标记已发布（OutboxPublisher 发 Kafka 成功后调用）。 */
    public Mono<Void> markPublished(String id) {
        return db.sql("UPDATE finance_outbox SET published_at = now() WHERE id = CAST(:id AS uuid)")
                .bind("id", id).then();
    }

    /** outbox 行（发布器用）。{@code payloadJson} 为 payload 的 JSON 字符串。 */
    public record OutboxRow(String id, String eventId, String eventType,
                            String aggregateType, String aggregateId, String payloadJson) {}
}
