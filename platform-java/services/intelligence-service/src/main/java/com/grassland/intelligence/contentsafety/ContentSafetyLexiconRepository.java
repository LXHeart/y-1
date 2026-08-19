package com.grassland.intelligence.contentsafety;

import io.r2dbc.spi.Readable;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Version repository for the server-only content-safety lexicon payload. */
@Repository
public class ContentSafetyLexiconRepository {

    private static final String COLUMNS =
            "id::text, label, payload::text, status, created_by, created_at, activated_at";
    private final DatabaseClient db;

    public ContentSafetyLexiconRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Long> count() {
        return db.sql("SELECT COUNT(*)::bigint AS count FROM content_safety_lexicon_version")
                .map(row -> row.get("count", Long.class)).one().defaultIfEmpty(0L);
    }

    public Mono<Version> insertSeed(String label, String payload) {
        return db.sql("""
                INSERT INTO content_safety_lexicon_version(label, payload, status, created_by, activated_at)
                VALUES (:label, CAST(:payload AS jsonb), 'active', 'system', now())
                ON CONFLICT (label) DO NOTHING
                RETURNING %s
                """.formatted(COLUMNS))
                .bind("label", label).bind("payload", payload)
                .map((row, metadata) -> map(row)).one()
                .switchIfEmpty(findActive());
    }

    public Mono<Version> createDraft(String label, String payload, String createdBy) {
        return db.sql("""
                INSERT INTO content_safety_lexicon_version(label, payload, status, created_by)
                VALUES (:label, CAST(:payload AS jsonb), 'draft', :createdBy)
                RETURNING %s
                """.formatted(COLUMNS))
                .bind("label", label).bind("payload", payload).bind("createdBy", createdBy)
                .map((row, metadata) -> map(row)).one();
    }

    public Mono<Version> findActive() {
        return db.sql("SELECT " + COLUMNS
                        + " FROM content_safety_lexicon_version WHERE status='active' LIMIT 1")
                .map((row, metadata) -> map(row)).one();
    }

    public Mono<Version> findById(UUID id) {
        return db.sql("SELECT " + COLUMNS
                        + " FROM content_safety_lexicon_version WHERE id=CAST(:id AS uuid)")
                .bind("id", id.toString()).map((row, metadata) -> map(row)).one();
    }

    public Flux<Version> list() {
        return db.sql("SELECT " + COLUMNS
                        + " FROM content_safety_lexicon_version ORDER BY created_at DESC, id DESC")
                .map((row, metadata) -> map(row)).all();
    }

    public Mono<Void> retireCurrentActive() {
        return db.sql("""
                UPDATE content_safety_lexicon_version
                SET status='retired'
                WHERE status='active'
                """).then();
    }

    public Mono<Version> activateDraft(UUID id) {
        return db.sql("""
                UPDATE content_safety_lexicon_version
                SET status='active', activated_at=now()
                WHERE id=CAST(:id AS uuid) AND status='draft'
                RETURNING %s
                """.formatted(COLUMNS))
                .bind("id", id.toString()).map((row, metadata) -> map(row)).one();
    }

    public Mono<Version> retireDraft(UUID id) {
        return db.sql("""
                UPDATE content_safety_lexicon_version SET status='retired'
                WHERE id=CAST(:id AS uuid) AND status='draft'
                RETURNING %s
                """.formatted(COLUMNS))
                .bind("id", id.toString()).map((row, metadata) -> map(row)).one();
    }

    private static Version map(Readable row) {
        return new Version(
                UUID.fromString(row.get("id", String.class)),
                row.get("label", String.class), row.get("payload", String.class),
                row.get("status", String.class), row.get("created_by", String.class),
                instant(row.get("created_at", OffsetDateTime.class)),
                instant(row.get("activated_at", OffsetDateTime.class)));
    }

    private static java.time.Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public record Version(
            UUID id, String label, String payload, String status, String createdBy,
            java.time.Instant createdAt, java.time.Instant activatedAt) {}
}
