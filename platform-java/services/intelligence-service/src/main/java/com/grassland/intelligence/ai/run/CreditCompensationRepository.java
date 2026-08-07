package com.grassland.intelligence.ai.run;

import java.time.Duration;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Durable, lease-fenced credit compensation intents for failed platform runs. */
@Component
public class CreditCompensationRepository {

    private static final int MAX_REASON_LENGTH = 512;
    private static final int MAX_ERROR_CODE_LENGTH = 64;
    private final DatabaseClient db;

    public CreditCompensationRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Void> enqueue(
            UUID runId, UUID consumeOperationId, String accountId, String feature, String reason) {
        return db.sql("""
                INSERT INTO ai_credit_compensation(
                    run_id, consume_operation_id, account_id, feature, reason)
                VALUES (CAST(:runId AS uuid), CAST(:operationId AS uuid), :accountId, :feature, :reason)
                ON CONFLICT (consume_operation_id) DO UPDATE
                SET run_id = CASE WHEN ai_credit_compensation.standalone
                                  THEN EXCLUDED.run_id ELSE ai_credit_compensation.run_id END,
                    actual_run_id = CASE WHEN ai_credit_compensation.standalone
                                         THEN EXCLUDED.actual_run_id
                                         ELSE ai_credit_compensation.actual_run_id END,
                    standalone = false,
                    updated_at = now()
                """)
                .bind("runId", runId.toString())
                .bind("operationId", consumeOperationId.toString())
                .bind("accountId", accountId)
                .bind("feature", feature)
                .bind("reason", truncate(reason, MAX_REASON_LENGTH, "AI run failed"))
                .then();
    }

    public Mono<Void> enqueueUnknownConsume(
            UUID consumeOperationId, String accountId, String feature, String reason) {
        return db.sql("""
                INSERT INTO ai_credit_compensation(
                    run_id, consume_operation_id, account_id, feature, reason, standalone)
                VALUES (CAST(:operationId AS uuid), CAST(:operationId AS uuid),
                        :accountId, :feature, :reason, true)
                ON CONFLICT (consume_operation_id) DO NOTHING
                """)
                .bind("operationId", consumeOperationId.toString())
                .bind("accountId", accountId)
                .bind("feature", feature)
                .bind("reason", truncate(reason, MAX_REASON_LENGTH, "AI credit outcome unknown"))
                .then();
    }

    public Flux<CompensationClaim> claimBatch(int limit, UUID claimToken, Duration leaseDuration) {
        return db.sql("""
                WITH candidates AS (
                    SELECT id
                    FROM ai_credit_compensation
                    WHERE status = 'pending'
                      AND next_attempt_at <= now()
                      AND (claimed_until IS NULL OR claimed_until <= now())
                    ORDER BY next_attempt_at, created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT :limit
                )
                UPDATE ai_credit_compensation target
                SET claim_token = CAST(:claimToken AS uuid),
                    claimed_until = now() + (:leaseMillis * interval '1 millisecond'),
                    attempt_count = target.attempt_count + 1,
                    last_error_code = NULL,
                    updated_at = now()
                FROM candidates
                WHERE target.id = candidates.id
                RETURNING target.id::text, target.actual_run_id::text AS run_id,
                          target.consume_operation_id::text,
                          target.account_id, target.feature, target.reason,
                          target.claim_token::text, target.attempt_count
                """)
                .bind("claimToken", claimToken.toString())
                .bind("leaseMillis", Math.max(leaseDuration.toMillis(), 1L))
                .bind("limit", Math.max(limit, 1))
                .map(CreditCompensationRepository::mapClaim)
                .all();
    }

    public Mono<CompensationClaim> claimRun(UUID runId, UUID claimToken, Duration leaseDuration) {
        return db.sql("""
                UPDATE ai_credit_compensation
                SET claim_token = CAST(:claimToken AS uuid),
                    claimed_until = now() + (:leaseMillis * interval '1 millisecond'),
                    attempt_count = attempt_count + 1,
                    last_error_code = NULL,
                    updated_at = now()
                WHERE actual_run_id = CAST(:runId AS uuid)
                  AND status = 'pending'
                  AND next_attempt_at <= now()
                  AND (claimed_until IS NULL OR claimed_until <= now())
                RETURNING id::text, actual_run_id::text AS run_id, consume_operation_id::text,
                          account_id, feature, reason, claim_token::text, attempt_count
                """)
                .bind("claimToken", claimToken.toString())
                .bind("leaseMillis", Math.max(leaseDuration.toMillis(), 1L))
                .bind("runId", runId.toString())
                .map(CreditCompensationRepository::mapClaim)
                .one();
    }

    public Mono<Boolean> markCompleted(UUID id, UUID claimToken) {
        return db.sql("""
                UPDATE ai_credit_compensation
                SET status = 'completed', completed_at = now(),
                    claim_token = NULL, claimed_until = NULL,
                    last_error_code = NULL, updated_at = now()
                WHERE id = CAST(:id AS uuid)
                  AND claim_token = CAST(:claimToken AS uuid)
                  AND status = 'pending'
                """)
                .bind("id", id.toString())
                .bind("claimToken", claimToken.toString())
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    public Mono<Boolean> markCompletedByOperationId(UUID consumeOperationId) {
        return db.sql("""
                UPDATE ai_credit_compensation
                SET status = 'completed', completed_at = now(),
                    claim_token = NULL, claimed_until = NULL,
                    last_error_code = NULL, updated_at = now()
                WHERE consume_operation_id = CAST(:operationId AS uuid)
                  AND status = 'pending'
                  AND claim_token IS NULL
                """)
                .bind("operationId", consumeOperationId.toString())
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    public Mono<Boolean> markFailed(
            UUID id, UUID claimToken, Duration retryDelay, String errorCode) {
        return db.sql("""
                UPDATE ai_credit_compensation
                SET claim_token = NULL,
                    claimed_until = NULL,
                    next_attempt_at = now() + (:retryMillis * interval '1 millisecond'),
                    last_error_code = :errorCode,
                    updated_at = now()
                WHERE id = CAST(:id AS uuid)
                  AND claim_token = CAST(:claimToken AS uuid)
                  AND status = 'pending'
                """)
                .bind("retryMillis", Math.max(retryDelay.toMillis(), 1L))
                .bind("errorCode", truncate(errorCode, MAX_ERROR_CODE_LENGTH, "Unknown"))
                .bind("id", id.toString())
                .bind("claimToken", claimToken.toString())
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    public Mono<Boolean> markTerminalFailed(UUID id, UUID claimToken, String errorCode) {
        return db.sql("""
                UPDATE ai_credit_compensation
                SET status = 'failed', failed_at = now(),
                    claim_token = NULL, claimed_until = NULL,
                    last_error_code = :errorCode, updated_at = now()
                WHERE id = CAST(:id AS uuid)
                  AND claim_token = CAST(:claimToken AS uuid)
                  AND status = 'pending'
                """)
                .bind("errorCode", truncate(errorCode, MAX_ERROR_CODE_LENGTH, "Unknown"))
                .bind("id", id.toString())
                .bind("claimToken", claimToken.toString())
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    private static CompensationClaim mapClaim(io.r2dbc.spi.Readable row) {
        String runId = row.get("run_id", String.class);
        return new CompensationClaim(
                UUID.fromString(row.get("id", String.class)),
                runId == null ? null : UUID.fromString(runId),
                UUID.fromString(row.get("consume_operation_id", String.class)),
                row.get("account_id", String.class),
                row.get("feature", String.class),
                row.get("reason", String.class),
                UUID.fromString(row.get("claim_token", String.class)),
                row.get("attempt_count", Integer.class));
    }

    private static String truncate(String value, int maxLength, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value;
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    public record CompensationClaim(
            UUID id,
            UUID runId,
            UUID consumeOperationId,
            String accountId,
            String feature,
            String reason,
            UUID claimToken,
            int attemptCount) {}
}
