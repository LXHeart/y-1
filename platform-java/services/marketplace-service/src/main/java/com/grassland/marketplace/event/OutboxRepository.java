package com.grassland.marketplace.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * marketplace outbox 写入（复刻 identity 2A 精简版）。表由 Flyway V1 建；本 slice 仅 append，Kafka 发布器留 4B。
 * payload 用本地 {@link ObjectMapper} 序列化为 JSON（Boot 4 的 Jackson autoconfig 在独立模块，marketplace 未引入）。
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
                    INSERT INTO marketplace_outbox (event_id, event_type, aggregate_type, aggregate_id, payload)
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

    /** 某报名最近一次资金预留失败原因（Slice 4F 轮询端点用：读 ApplicationReservationFailed outbox 事件的 reason）。
     *  无 / reason 空 → empty Mono（调用方据此判「无补偿记录」）。 */
    /** 未发布的 outbox 行（published_at IS NULL），按 id 升序取 limit 条。OutboxPublisher 轮询用。 */
    public reactor.core.publisher.Flux<OutboxRow> findUnpublished(int limit) {
        return db.sql("""
                SELECT id::text, event_id, event_type, aggregate_type, aggregate_id, payload::text
                FROM marketplace_outbox WHERE published_at IS NULL
                ORDER BY id LIMIT :limit
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
        return db.sql("UPDATE marketplace_outbox SET published_at = now() WHERE id = CAST(:id AS bigint)")
                .bind("id", id).then();
    }

    /** outbox 行（发布器用）。{@code payloadJson} 为 payload 的 JSON 字符串。 */
    public record OutboxRow(String id, String eventId, String eventType,
                            String aggregateType, String aggregateId, String payloadJson) {}

    public Mono<String> latestReservationFailureReason(String applicationId) {
        return db.sql("""
                SELECT payload->>'reason' AS reason FROM marketplace_outbox
                WHERE event_type = 'ApplicationReservationFailed' AND aggregate_id = :appId
                ORDER BY id DESC LIMIT 1
                """)
                .bind("appId", applicationId)
                .map(r -> r.get("reason", String.class)).one()
                .filter(reason -> reason != null && !reason.isBlank());
    }

    /** 某报名最近一次结算结局（Slice 5A/6A 轮询用）：EngagementSettled→{status:settled}，
     *  SettlementHeld→{status:held, reason}（reason 来自 outbox payload，如 open_dispute）；无→empty。 */
    public Mono<java.util.Map<String, Object>> latestSettlementStatus(String applicationId) {
        return db.sql("""
                SELECT event_type AS et, payload->>'reason' AS reason FROM marketplace_outbox
                WHERE event_type IN ('EngagementSettled','SettlementHeld') AND aggregate_id = :appId
                ORDER BY id DESC LIMIT 1
                """)
                .bind("appId", applicationId)
                .map(r -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("status", "EngagementSettled".equals(r.get("et", String.class)) ? "settled" : "held");
                    String reason = r.get("reason", String.class);
                    if (reason != null) {
                        m.put("reason", reason);
                    }
                    return m;
                }).one();
    }
}
