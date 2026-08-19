package com.grassland.intelligence.contentsafety;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static com.grassland.intelligence.config.R2dbcBindings.nullable;

/** Stores text fingerprints only; source text is deliberately never persisted. */
@Repository
public class ContentFingerprintRepository {

    private final DatabaseClient db;

    public ContentFingerprintRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Fingerprint> insert(Fingerprint value) {
        return db.sql("""
                INSERT INTO content_fingerprint(
                    owner_account_id, task_id, application_id, platform, content_form,
                    simhash, shingle_count, source_kind)
                VALUES (:owner, :taskId, :applicationId, :platform, :contentForm,
                    :simhash, :shingleCount, :sourceKind)
                RETURNING id::text, owner_account_id, task_id, application_id, platform,
                    content_form, simhash, shingle_count, source_kind, created_at
                """)
                .bind("owner", value.ownerAccountId())
                .bind("taskId", nullable(value.taskId(), String.class))
                .bind("applicationId", nullable(value.applicationId(), String.class))
                .bind("platform", nullable(value.platform(), String.class))
                .bind("contentForm", nullable(value.contentForm(), String.class))
                .bind("simhash", value.simhash())
                .bind("shingleCount", value.shingleCount())
                .bind("sourceKind", value.sourceKind())
                .map((row, metadata) -> map(row)).one();
    }

    public Flux<Fingerprint> findCandidates(String ownerAccountId, String taskId, Instant ownerCutoff) {
        return db.sql("""
                SELECT id::text, owner_account_id, task_id, application_id, platform,
                    content_form, simhash, shingle_count, source_kind, created_at
                FROM content_fingerprint
                WHERE (owner_account_id=:owner AND created_at >= :ownerCutoff)
                   OR (:taskId IS NOT NULL AND task_id=:taskId)
                ORDER BY created_at DESC
                LIMIT 1000
                """)
                .bind("owner", ownerAccountId)
                .bind("ownerCutoff", ownerCutoff.atOffset(ZoneOffset.UTC))
                .bind("taskId", nullable(taskId, String.class))
                .map((row, metadata) -> map(row)).all();
    }

    private static Fingerprint map(Readable row) {
        OffsetDateTime created = row.get("created_at", OffsetDateTime.class);
        return new Fingerprint(
                UUID.fromString(row.get("id", String.class)),
                row.get("owner_account_id", String.class), row.get("task_id", String.class),
                row.get("application_id", String.class), row.get("platform", String.class),
                row.get("content_form", String.class), row.get("simhash", Long.class),
                row.get("shingle_count", Integer.class), row.get("source_kind", String.class),
                created == null ? null : created.toInstant());
    }

    public record Fingerprint(
            UUID id, String ownerAccountId, String taskId, String applicationId,
            String platform, String contentForm, long simhash, int shingleCount,
            String sourceKind, Instant createdAt) {}
}
