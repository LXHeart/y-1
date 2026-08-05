package com.grassland.intelligence.media;

import io.r2dbc.spi.Readable;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** KYB 证据留存引用的最小数据访问层。 */
@Component
public class KybMediaRetentionRepository {

    private static final Duration LEGACY_LEASE = Duration.ofDays(30);
    private static final String RETURNING = """
            media_reference_id::text, reference_id::text, organization_id,
            reference_type, lease_until, retained_until, released_at
            """;

    private final DatabaseClient db;

    public KybMediaRetentionRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Boolean> retain(UUID mediaId, String organizationId, UUID referenceId) {
        return upsertLease(mediaId, organizationId, referenceId, "attachment", LEGACY_LEASE)
                .hasElement();
    }

    /** Acquire or renew a rolling lease. GREATEST makes retries idempotent and prevents shortening. */
    public Mono<Retention> upsertLease(UUID mediaId, String organizationId, UUID referenceId,
                                       String referenceType, Duration leaseDuration) {
        long leaseMillis = Math.max(leaseDuration.toMillis(), 1L);
        return db.sql("""
                WITH locked_media AS MATERIALIZED (
                    SELECT m.id, m.organization_id
                    FROM media_reference m
                    WHERE m.id = CAST(:media AS uuid)
                      AND m.organization_id = :org AND m.purpose = 'merchant_kyb'
                      AND m.domain_type = 'merchant_kyb' AND m.domain_id = :org
                      AND m.status = 'active' AND (m.expires_at IS NULL OR m.expires_at > now())
                    FOR UPDATE
                )
                INSERT INTO media_kyb_retention(
                    media_reference_id, reference_id, organization_id, reference_type, lease_until)
                SELECT id, CAST(:reference AS uuid), organization_id, :referenceType,
                       now() + (:leaseMillis * interval '1 millisecond')
                FROM locked_media
                ON CONFLICT (media_reference_id, reference_id)
                DO UPDATE SET
                    lease_until = GREATEST(media_kyb_retention.lease_until, excluded.lease_until),
                    released_at = NULL,
                    release_requested_at = NULL,
                    updated_at = now()
                WHERE media_kyb_retention.organization_id = excluded.organization_id
                  AND media_kyb_retention.reference_type = excluded.reference_type
                RETURNING %s
                """.formatted(RETURNING))
                .bind("media", mediaId).bind("reference", referenceId)
                .bind("org", organizationId).bind("referenceType", referenceType)
                .bind("leaseMillis", leaseMillis)
                .map(KybMediaRetentionRepository::map).one();
    }

    /** Seal a review token until an absolute audit deadline. Existing deadlines can only move forward. */
    public Mono<Retention> seal(UUID mediaId, String organizationId, UUID referenceId,
                                String referenceType, Instant retainUntil) {
        return db.sql("""
                WITH locked_media AS MATERIALIZED (
                    SELECT m.id, m.organization_id
                    FROM media_reference m
                    WHERE m.id = CAST(:media AS uuid)
                      AND m.organization_id = :org AND m.purpose = 'merchant_kyb'
                      AND m.domain_type = 'merchant_kyb' AND m.domain_id = :org
                      AND m.status = 'active' AND (m.expires_at IS NULL OR m.expires_at > now())
                    FOR UPDATE
                )
                INSERT INTO media_kyb_retention(
                    media_reference_id, reference_id, organization_id, reference_type, retained_until)
                SELECT id, CAST(:reference AS uuid), organization_id, :referenceType, :retainUntil
                FROM locked_media
                ON CONFLICT (media_reference_id, reference_id)
                DO UPDATE SET
                    retained_until = GREATEST(media_kyb_retention.retained_until, excluded.retained_until),
                    released_at = NULL,
                    release_requested_at = NULL,
                    updated_at = now()
                WHERE media_kyb_retention.organization_id = excluded.organization_id
                  AND media_kyb_retention.reference_type = excluded.reference_type
                RETURNING %s
                """.formatted(RETURNING))
                .bind("media", mediaId).bind("reference", referenceId)
                .bind("org", organizationId).bind("referenceType", referenceType)
                .bind("retainUntil", retainUntil.atOffset(ZoneOffset.UTC))
                .map(KybMediaRetentionRepository::map).one();
    }

    public Mono<Boolean> release(UUID mediaId, String organizationId, UUID referenceId) {
        return db.sql("""
                UPDATE media_kyb_retention
                SET release_requested_at = now(),
                    released_at = CASE
                        WHEN retained_until IS NULL OR retained_until <= now() THEN now()
                        ELSE released_at
                    END,
                    lease_until = CASE
                        WHEN retained_until IS NULL OR retained_until <= now() THEN NULL
                        ELSE lease_until
                    END,
                    updated_at = now()
                WHERE media_reference_id = CAST(:media AS uuid)
                  AND reference_id = CAST(:reference AS uuid)
                  AND organization_id = :org AND released_at IS NULL
                RETURNING released_at IS NOT NULL AS released
                """)
                .bind("media", mediaId).bind("reference", referenceId).bind("org", organizationId)
                .map(row -> Boolean.TRUE.equals(row.get("released", Boolean.class)))
                .one().defaultIfEmpty(false);
    }

    public Mono<Boolean> isRetained(UUID mediaId) {
        return db.sql("SELECT EXISTS(SELECT 1 FROM media_kyb_retention"
                        + " WHERE media_reference_id=CAST(:media AS uuid) AND released_at IS NULL"
                        + " AND (lease_until > now() OR retained_until > now()))")
                .bind("media", mediaId)
                .map(row -> Boolean.TRUE.equals(row.get(0, Boolean.class)))
                .one().defaultIfEmpty(false);
    }

    private static Retention map(Readable row) {
        return new Retention(
                UUID.fromString(row.get("media_reference_id", String.class)),
                UUID.fromString(row.get("reference_id", String.class)),
                row.get("organization_id", String.class),
                row.get("reference_type", String.class),
                instant(row.get("lease_until", OffsetDateTime.class)),
                instant(row.get("retained_until", OffsetDateTime.class)),
                instant(row.get("released_at", OffsetDateTime.class)));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public record Retention(
            UUID mediaReferenceId,
            UUID referenceId,
            String organizationId,
            String referenceType,
            Instant leaseUntil,
            Instant retainedUntil,
            Instant releasedAt) {}
}
