package com.grassland.intelligence.media;

import io.r2dbc.spi.Readable;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * {@code store_media_moderation} 仓储（缺口清偿之五）：门店公开媒体多模态审核结论。
 * 一行一媒体（PK=media_reference_id），重复审核 UPSERT 覆盖；无行=未审（advisory 降级）。
 */
@Component
public class StoreMediaModerationRepository {

    private final DatabaseClient db;

    public StoreMediaModerationRepository(DatabaseClient db) {
        this.db = db;
    }

    public record ModerationRow(UUID mediaReferenceId, String status, String findingsJson,
                                String model, String runId, Instant moderatedAt) {}

    /** UPSERT：同一媒体的最新结论生效（保留旧结论语义 = 覆盖）。 */
    public Mono<ModerationRow> upsert(ModerationRow row) {
        var spec = db.sql("""
                        INSERT INTO store_media_moderation (
                            media_reference_id, status, findings, model, run_id, moderated_at)
                        VALUES (CAST(:id AS uuid), :status, CAST(:findings AS jsonb), :model, :runId, :moderatedAt)
                        ON CONFLICT (media_reference_id) DO UPDATE SET
                            status = excluded.status,
                            findings = excluded.findings,
                            model = excluded.model,
                            run_id = excluded.run_id,
                            moderated_at = excluded.moderated_at
                        RETURNING media_reference_id::text, status, findings::text, model, run_id, moderated_at
                        """)
                .bind("id", row.mediaReferenceId().toString())
                .bind("status", row.status())
                .bind("findings", row.findingsJson())
                .bind("moderatedAt", row.moderatedAt().atOffset(java.time.ZoneOffset.UTC));
        spec = bindNullable(spec, "model", row.model());
        spec = bindNullable(spec, "runId", row.runId());
        return spec.map(StoreMediaModerationRepository::mapRow).one();
    }

    public Mono<ModerationRow> find(UUID mediaReferenceId) {
        return db.sql("SELECT media_reference_id::text, status, findings::text, model, run_id, moderated_at"
                        + " FROM store_media_moderation WHERE media_reference_id=CAST(:id AS uuid)")
                .bind("id", mediaReferenceId.toString())
                .map(StoreMediaModerationRepository::mapRow)
                .one();
    }

    public Mono<Boolean> exists(UUID mediaReferenceId) {
        return db.sql("SELECT 1 FROM store_media_moderation WHERE media_reference_id=CAST(:id AS uuid)")
                .bind("id", mediaReferenceId.toString())
                .map(row -> Boolean.TRUE).one().defaultIfEmpty(false);
    }

    private static ModerationRow mapRow(Readable row) {
        OffsetDateTime at = row.get("moderated_at", OffsetDateTime.class);
        return new ModerationRow(
                UUID.fromString(row.get("media_reference_id", String.class)),
                row.get("status", String.class),
                row.get("findings", String.class),
                row.get("model", String.class),
                row.get("run_id", String.class),
                at == null ? null : at.toInstant());
    }

    private static DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, String name, String value) {
        return value == null || value.isBlank()
                ? spec.bindNull(name, String.class)
                : spec.bind(name, value);
    }
}
