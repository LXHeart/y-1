package com.grassland.marketplace.ops;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * ops_dlt_message 数据访问（GL-P1-OPS-001 Stage 2）。
 *
 * <p>{@code offset} 是 SQL 保留字，列名一律带引号。
 */
@Component
public class OpsDltMessageRepository {

    private static final String SELECT_COLS =
            "id::text, topic, partition, \"offset\", original_topic, message_key, payload,"
                    + " error_summary, status, replayed_at, discarded_at, created_at";

    private final DatabaseClient db;

    public OpsDltMessageRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 登记死信。位点冲突 → empty（同一条消息被重复消费时不重复登记）。 */
    public Mono<OpsDltMessage> insertIfAbsent(String topic, int partition, long offset, String originalTopic,
                                              String messageKey, String payload, String errorSummary) {
        var spec = db.sql("""
                INSERT INTO ops_dlt_message(id, topic, partition, "offset", original_topic,
                        message_key, payload, error_summary)
                VALUES (CAST(:id AS uuid), :topic, :partition, :offset, :originalTopic,
                        :messageKey, :payload, :errorSummary)
                ON CONFLICT (topic, partition, "offset") DO NOTHING
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", UUID.randomUUID().toString())
                .bind("topic", topic)
                .bind("partition", partition)
                .bind("offset", offset)
                .bind("originalTopic", originalTopic)
                .bind("payload", payload);
        spec = bindNullable(spec, "messageKey", messageKey);
        spec = bindNullable(spec, "errorSummary", errorSummary);
        return spec.map(OpsDltMessageRepository::map).one();
    }

    public Mono<OpsDltMessage> findById(String id) {
        return db.sql("SELECT " + SELECT_COLS + " FROM ops_dlt_message WHERE id = CAST(:id AS uuid)")
                .bind("id", id)
                .map(OpsDltMessageRepository::map).one();
    }

    public Mono<OpsDltMessage> findByPosition(String topic, int partition, long offset) {
        return db.sql("SELECT " + SELECT_COLS + " FROM ops_dlt_message"
                + " WHERE topic = :topic AND partition = :partition AND \"offset\" = :offset")
                .bind("topic", topic).bind("partition", partition).bind("offset", offset)
                .map(OpsDltMessageRepository::map).all().next();
    }

    /** 列表。{@code status} 省略 → 仅 pending（待处置）。 */
    public Flux<OpsDltMessage> list(String status, int limit) {
        String where = status == null || status.isBlank() ? "status = 'pending'" : "status = :status";
        var spec = db.sql("SELECT " + SELECT_COLS + " FROM ops_dlt_message WHERE " + where
                + " ORDER BY created_at, id LIMIT :limit")
                .bind("limit", limit);
        if (status != null && !status.isBlank()) {
            spec = spec.bind("status", status);
        }
        return spec.map(OpsDltMessageRepository::map).all();
    }

    /**
     * 标记已重投 / 已弃置。只吃 {@code pending} —— 非 pending → empty，调用方映射 409。
     *
     * <p>弃置只改状态，<b>不删行也不删 Kafka 消息</b>：死信是审计对象，「谁在什么时候放弃了哪条消息」
     * 必须留痕。Kafka 侧的 retention 清理归 GL-P3-PLATFORM-001。
     */
    public Mono<OpsDltMessage> markReplayed(String id) {
        return transition(id, "replayed", "replayed_at");
    }

    public Mono<OpsDltMessage> markDiscarded(String id) {
        return transition(id, "discarded", "discarded_at");
    }

    private Mono<OpsDltMessage> transition(String id, String status, String tsColumn) {
        return db.sql("""
                UPDATE ops_dlt_message SET status = :status, %s = now()
                WHERE id = CAST(:id AS uuid) AND status = 'pending'
                RETURNING %s
                """.formatted(tsColumn, SELECT_COLS))
                .bind("id", id).bind("status", status)
                .map(OpsDltMessageRepository::map).one();
    }

    private static OpsDltMessage map(Readable row) {
        return new OpsDltMessage(
                row.get("id", String.class),
                row.get("topic", String.class),
                intValue(row.get("partition", Integer.class)),
                longValue(row.get("offset", Long.class)),
                row.get("original_topic", String.class),
                row.get("message_key", String.class),
                row.get("payload", String.class),
                row.get("error_summary", String.class),
                row.get("status", String.class),
                toInstant(row.get("replayed_at", OffsetDateTime.class)),
                toInstant(row.get("discarded_at", OffsetDateTime.class)),
                toInstant(row.get("created_at", OffsetDateTime.class)));
    }

    private static int intValue(Integer raw) {
        return raw == null ? 0 : raw;
    }

    private static long longValue(Long raw) {
        return raw == null ? 0L : raw;
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }
}
