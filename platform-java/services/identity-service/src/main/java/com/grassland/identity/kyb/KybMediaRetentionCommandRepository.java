package com.grassland.identity.kyb;

import io.r2dbc.spi.Readable;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Durable desired-state repository for KYB media retention. */
@Component
public class KybMediaRetentionCommandRepository {

    private static final int MAX_ERROR_CODE_LENGTH = 64;
    private static final String RETURNING = """
            target.media_reference_id::text AS media_reference_id,
            target.reference_id::text AS reference_id,
            target.organization_id::text AS organization_id,
            target.reference_type, target.desired_state, target.retain_until,
            target.remote_lease_until, target.sync_status, target.attempt_count,
            target.claim_token::text AS claim_token
            """;

    private final DatabaseClient db;

    public KybMediaRetentionCommandRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Void> upsertLive(UUID mediaId, UUID referenceId, String organizationId,
                                 String referenceType, Instant remoteLeaseUntil) {
        return db.sql("""
                INSERT INTO kyb_media_retention_sync(
                    media_reference_id, reference_id, organization_id, reference_type,
                    desired_state, remote_lease_until, sync_status)
                VALUES (CAST(:media AS uuid), CAST(:reference AS uuid), CAST(:org AS uuid),
                        :referenceType, 'live', :remoteLeaseUntil, :syncStatus)
                ON CONFLICT (media_reference_id, reference_id)
                DO UPDATE SET
                    remote_lease_until = GREATEST(
                        kyb_media_retention_sync.remote_lease_until, excluded.remote_lease_until),
                    sync_status = excluded.sync_status,
                    next_attempt_at = now(),
                    claimed_until = NULL,
                    claim_token = NULL,
                    last_error_code = NULL,
                    updated_at = now()
                WHERE kyb_media_retention_sync.organization_id = excluded.organization_id
                  AND kyb_media_retention_sync.reference_type = excluded.reference_type
                  AND kyb_media_retention_sync.desired_state = 'live'
                """)
                .bind("media", mediaId).bind("reference", referenceId)
                .bind("org", organizationId).bind("referenceType", referenceType)
                .bind("syncStatus", remoteLeaseUntil == null ? "pending" : "synced")
                .bind("remoteLeaseUntil", offset(remoteLeaseUntil))
                .then();
    }

    public Mono<Boolean> markReleased(UUID mediaId, UUID referenceId, String organizationId) {
        return db.sql("""
                UPDATE kyb_media_retention_sync
                SET desired_state='released', retain_until=NULL, sync_status='pending',
                    next_attempt_at=now(), claimed_until=NULL, claim_token=NULL,
                    last_error_code=NULL, updated_at=now()
                WHERE media_reference_id=CAST(:media AS uuid)
                  AND reference_id=CAST(:reference AS uuid)
                  AND organization_id=CAST(:org AS uuid)
                """)
                .bind("media", mediaId).bind("reference", referenceId).bind("org", organizationId)
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    public Mono<Boolean> markReleasedSynced(UUID mediaId, UUID referenceId, String organizationId) {
        return db.sql("""
                UPDATE kyb_media_retention_sync
                SET sync_status='synced', remote_lease_until=NULL, next_attempt_at=now(),
                    claimed_until=NULL, claim_token=NULL, last_error_code=NULL, updated_at=now()
                WHERE media_reference_id=CAST(:media AS uuid)
                  AND reference_id=CAST(:reference AS uuid)
                  AND organization_id=CAST(:org AS uuid)
                  AND desired_state='released'
                """)
                .bind("media", mediaId).bind("reference", referenceId).bind("org", organizationId)
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    public Mono<Long> sealReference(UUID referenceId, String organizationId, Instant retainUntil) {
        return db.sql("""
                UPDATE kyb_media_retention_sync
                SET desired_state='sealed',
                    retain_until=GREATEST(retain_until, :retainUntil),
                    sync_status='pending', next_attempt_at=now(),
                    claimed_until=NULL, claim_token=NULL, last_error_code=NULL, updated_at=now()
                WHERE reference_id=CAST(:reference AS uuid)
                  AND organization_id=CAST(:org AS uuid)
                  AND reference_type='review_request'
                  AND desired_state <> 'released'
                """)
                .bind("reference", referenceId).bind("org", organizationId)
                .bind("retainUntil", retainUntil.atOffset(ZoneOffset.UTC))
                .fetch().rowsUpdated();
    }

    public Mono<Long> expireSealed() {
        return db.sql("""
                UPDATE kyb_media_retention_sync
                SET desired_state='released', retain_until=NULL, sync_status='pending',
                    next_attempt_at=now(), claimed_until=NULL, claim_token=NULL,
                    last_error_code=NULL, updated_at=now()
                WHERE desired_state='sealed' AND retain_until <= now()
                """)
                .fetch().rowsUpdated();
    }

    public Flux<KybMediaRetentionCommand> claimBatch(
            int limit, UUID claimToken, Duration claimLease, Duration renewAhead) {
        return db.sql("""
                WITH candidates AS (
                    SELECT media_reference_id, reference_id
                    FROM kyb_media_retention_sync
                    WHERE next_attempt_at <= now()
                      AND (claimed_until IS NULL OR claimed_until <= now())
                      AND (sync_status='pending'
                           OR (desired_state='live' AND
                               (remote_lease_until IS NULL
                                OR remote_lease_until <= now() + (:renewAheadMillis * interval '1 millisecond'))))
                    ORDER BY next_attempt_at, updated_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT :limit
                )
                UPDATE kyb_media_retention_sync target
                SET claimed_until=now() + (:claimLeaseMillis * interval '1 millisecond'),
                    claim_token=CAST(:claimToken AS uuid),
                    attempt_count=target.attempt_count + 1,
                    last_error_code=NULL,
                    updated_at=now()
                FROM candidates
                WHERE target.media_reference_id=candidates.media_reference_id
                  AND target.reference_id=candidates.reference_id
                RETURNING %s
                """.formatted(RETURNING))
                .bind("renewAheadMillis", Math.max(renewAhead.toMillis(), 1L))
                .bind("claimLeaseMillis", Math.max(claimLease.toMillis(), 1L))
                .bind("claimToken", claimToken).bind("limit", Math.max(limit, 1))
                .map(KybMediaRetentionCommandRepository::map).all();
    }

    public Mono<Boolean> markSynced(
            UUID mediaId, UUID referenceId, UUID claimToken, Instant remoteDeadline) {
        return db.sql("""
                UPDATE kyb_media_retention_sync
                SET sync_status='synced', remote_lease_until=:remoteDeadline,
                    claimed_until=NULL, claim_token=NULL, last_error_code=NULL, updated_at=now()
                WHERE media_reference_id=CAST(:media AS uuid)
                  AND reference_id=CAST(:reference AS uuid)
                  AND claim_token=CAST(:claimToken AS uuid)
                """)
                .bind("media", mediaId).bind("reference", referenceId).bind("claimToken", claimToken)
                .bind("remoteDeadline", offset(remoteDeadline))
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    public Mono<Boolean> markFailure(
            UUID mediaId, UUID referenceId, UUID claimToken, Duration retryDelay, String errorCode) {
        return db.sql("""
                UPDATE kyb_media_retention_sync
                SET sync_status='pending',
                    next_attempt_at=now() + (:retryMillis * interval '1 millisecond'),
                    claimed_until=NULL, claim_token=NULL, last_error_code=:errorCode, updated_at=now()
                WHERE media_reference_id=CAST(:media AS uuid)
                  AND reference_id=CAST(:reference AS uuid)
                  AND claim_token=CAST(:claimToken AS uuid)
                """)
                .bind("media", mediaId).bind("reference", referenceId).bind("claimToken", claimToken)
                .bind("retryMillis", Math.max(retryDelay.toMillis(), 1L))
                .bind("errorCode", normalize(errorCode))
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    private static KybMediaRetentionCommand map(Readable row) {
        return new KybMediaRetentionCommand(
                UUID.fromString(row.get("media_reference_id", String.class)),
                UUID.fromString(row.get("reference_id", String.class)),
                row.get("organization_id", String.class),
                row.get("reference_type", String.class),
                row.get("desired_state", String.class),
                instant(row.get("retain_until", OffsetDateTime.class)),
                instant(row.get("remote_lease_until", OffsetDateTime.class)),
                row.get("sync_status", String.class),
                value(row.get("attempt_count", Integer.class)),
                UUID.fromString(row.get("claim_token", String.class)));
    }

    private static OffsetDateTime offset(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static String normalize(String value) {
        String normalized = value == null || value.isBlank() ? "Unknown" : value;
        return normalized.length() <= MAX_ERROR_CODE_LENGTH
                ? normalized : normalized.substring(0, MAX_ERROR_CODE_LENGTH);
    }
}
