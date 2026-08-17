package com.grassland.finance.judgereward;

import com.fasterxml.jackson.databind.JsonNode;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.TreeMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * finance 首个 Kafka 业务消费者的幂等表（{@code finance_inbox}，V19）。镜像 identity
 * {@code InboxRepository}：{@code recordIfAbsent} 用 {@code ON CONFLICT DO NOTHING} 插入，
 * 冲突（同 {@code (consumer_name, event_id)}）时回查校验 payload SHA-256——同 ID 异内容抛
 * {@link FinanceInboxContractException} 进 DLT，不静默覆盖。
 */
@Component
public class FinanceInboxRepository {

    private final DatabaseClient db;

    public FinanceInboxRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * @return {@code true} 本次插入了一行（新事件）；{@code false} 已处理过（幂等命中）；error = 契约冲突。
     */
    public Mono<Boolean> recordIfAbsent(
            String consumerName,
            ConsumerRecord<String, String> record,
            String eventId, String eventType, String aggregateType, String aggregateId,
            JsonNode payload) {
        String payloadSha256 = payloadSha256(payload);
        return db.sql("""
                        INSERT INTO finance_inbox
                            (consumer_name, event_id, event_type, aggregate_type, aggregate_id,
                             payload_sha256, source_topic, source_partition, source_offset)
                        VALUES (:consumerName, :eventId, :eventType, :aggregateType, :aggregateId,
                                :payloadSha256, :topic, :partition, :offset)
                        ON CONFLICT DO NOTHING
                        RETURNING event_id
                        """)
                .bind("consumerName", consumerName)
                .bind("eventId", eventId)
                .bind("eventType", eventType)
                .bind("aggregateType", aggregateType)
                .bind("aggregateId", aggregateId)
                .bind("payloadSha256", payloadSha256)
                .bind("topic", record.topic())
                .bind("partition", record.partition())
                .bind("offset", record.offset())
                .map((row, metadata) -> true)
                .one()
                .switchIfEmpty(validateExisting(consumerName, eventId, eventType,
                        aggregateType, aggregateId, payloadSha256));
    }

    private Mono<Boolean> validateExisting(
            String consumerName, String eventId, String eventType,
            String aggregateType, String aggregateId, String payloadSha256) {
        return db.sql("""
                        SELECT event_type, aggregate_type, aggregate_id, payload_sha256
                        FROM finance_inbox
                        WHERE consumer_name = :consumerName AND event_id = :eventId
                        """)
                .bind("consumerName", consumerName)
                .bind("eventId", eventId)
                .map(row -> new ExistingEvent(
                        row.get("event_type", String.class),
                        row.get("aggregate_type", String.class),
                        row.get("aggregate_id", String.class),
                        row.get("payload_sha256", String.class)))
                .one()
                .switchIfEmpty(Mono.error(new FinanceInboxContractException(
                        "source offset already belongs to a different event")))
                .flatMap(existing -> Objects.equals(existing.eventType(), eventType)
                        && Objects.equals(existing.aggregateType(), aggregateType)
                        && Objects.equals(existing.aggregateId(), aggregateId)
                        && Objects.equals(existing.payloadSha256(), payloadSha256)
                        ? Mono.just(false)
                        : Mono.error(new FinanceInboxContractException(
                                "conflicting finance event content for eventId " + eventId)));
    }

    /** 载荷在 inbox 前解析/规范化（契约错误立即抛，进 DLT 不重投）；SHA-256 对 canonical（键排序）JSON。 */
    static String payloadSha256(JsonNode payload) {
        try {
            byte[] canonical = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsBytes(canonicalize(payload));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception error) {
            throw new FinanceInboxContractException("finance event payload cannot be canonicalized", error);
        }
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode sorted =
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            Map<String, JsonNode> fields = new TreeMap<>();
            node.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, value) -> sorted.set(key, canonicalize(value)));
            return sorted;
        }
        if (node.isArray()) {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ArrayNode array = mapper.createArrayNode();
            node.forEach(value -> array.add(canonicalize(value)));
            return array;
        }
        return node.deepCopy();
    }

    private record ExistingEvent(
            String eventType, String aggregateType, String aggregateId, String payloadSha256) {}

    /** 契约错误（坏消息/内容冲突）：进 DLT 不重投。 */
    public static final class FinanceInboxContractException extends RuntimeException {
        public FinanceInboxContractException(String message) {
            super(message);
        }

        public FinanceInboxContractException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
