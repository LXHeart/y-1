package com.grassland.intelligence.ai.run;

import com.grassland.intelligence.credits.CreditSettlement;
import java.time.Duration;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Lease-fenced Finance settlement intents created atomically with successful AI runs. */
@Component
public class CreditUsageSettlementRepository {

    private static final int MAX_ERROR_CODE_LENGTH = 64;
    private final DatabaseClient db;

    public CreditUsageSettlementRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Void> enqueue(
            UUID runId, UUID consumeOperationId, String accountId, String feature,
            String policyVersion, int actualCents) {
        return db.sql("""
                INSERT INTO ai_credit_usage_settlement(
                    run_id, consume_operation_id, account_id, feature,
                    credits_cents_policy_version, actual_cents)
                VALUES (CAST(:runId AS uuid), CAST(:operationId AS uuid), :accountId, :feature,
                        :policyVersion, :actualCents)
                ON CONFLICT (run_id) DO NOTHING
                """)
                .bind("runId", runId.toString())
                .bind("operationId", consumeOperationId.toString())
                .bind("accountId", accountId)
                .bind("feature", feature)
                .bind("policyVersion", policyVersion)
                .bind("actualCents", actualCents)
                .then();
    }

    public Flux<SettlementClaim> claimBatch(int limit, UUID claimToken, Duration leaseDuration) {
        return db.sql("""
                WITH candidates AS (
                    SELECT id FROM ai_credit_usage_settlement
                    WHERE status = 'pending' AND next_attempt_at <= now()
                      AND (claimed_until IS NULL OR claimed_until <= now())
                    ORDER BY next_attempt_at, created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT :limit
                )
                UPDATE ai_credit_usage_settlement target
                SET claim_token = CAST(:claimToken AS uuid),
                    claimed_until = now() + (:leaseMillis * interval '1 millisecond'),
                    attempt_count = target.attempt_count + 1,
                    last_error_code = NULL,
                    updated_at = now()
                FROM candidates
                WHERE target.id = candidates.id
                RETURNING target.id::text, target.run_id::text,
                          target.consume_operation_id::text, target.account_id, target.feature,
                          target.credits_cents_policy_version, target.actual_cents,
                          target.claim_token::text, target.attempt_count
                """)
                .bind("claimToken", claimToken.toString())
                .bind("leaseMillis", Math.max(leaseDuration.toMillis(), 1L))
                .bind("limit", Math.max(limit, 1))
                .map(CreditUsageSettlementRepository::mapClaim)
                .all();
    }

    public Mono<Boolean> markCompleted(
            UUID id, UUID claimToken, CreditSettlement settlement) {
        return db.sql("""
                UPDATE ai_credit_usage_settlement
                SET status = 'completed', completed_at = now(),
                    claim_token = NULL, claimed_until = NULL, last_error_code = NULL,
                    charge_source = :source,
                    reserved_cents = :reservedCents,
                    reserved_credits = :reservedCredits,
                    actual_credits = :actualCredits,
                    adjustment_credits = :adjustmentCredits,
                    updated_at = now()
                WHERE id = CAST(:id AS uuid)
                  AND claim_token = CAST(:claimToken AS uuid)
                  AND status = 'pending'
                """)
                .bind("source", settlement.source().name().toLowerCase(java.util.Locale.ROOT))
                .bind("reservedCents", settlement.reservedCents())
                .bind("reservedCredits", settlement.reservedCredits())
                .bind("actualCredits", settlement.actualCredits())
                .bind("adjustmentCredits", settlement.adjustmentCredits())
                .bind("id", id.toString())
                .bind("claimToken", claimToken.toString())
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    public Mono<Boolean> markFailed(
            UUID id, UUID claimToken, Duration retryDelay, String errorCode) {
        return db.sql("""
                UPDATE ai_credit_usage_settlement
                SET claim_token = NULL, claimed_until = NULL,
                    next_attempt_at = now() + (:retryMillis * interval '1 millisecond'),
                    last_error_code = :errorCode, updated_at = now()
                WHERE id = CAST(:id AS uuid)
                  AND claim_token = CAST(:claimToken AS uuid)
                  AND status = 'pending'
                """)
                .bind("retryMillis", Math.max(retryDelay.toMillis(), 1L))
                .bind("errorCode", truncate(errorCode))
                .bind("id", id.toString())
                .bind("claimToken", claimToken.toString())
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    public Mono<Boolean> markTerminalFailed(UUID id, UUID claimToken, String errorCode) {
        return db.sql("""
                UPDATE ai_credit_usage_settlement
                SET status = 'failed', failed_at = now(),
                    claim_token = NULL, claimed_until = NULL,
                    last_error_code = :errorCode, updated_at = now()
                WHERE id = CAST(:id AS uuid)
                  AND claim_token = CAST(:claimToken AS uuid)
                  AND status = 'pending'
                """)
                .bind("errorCode", truncate(errorCode))
                .bind("id", id.toString())
                .bind("claimToken", claimToken.toString())
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    private static SettlementClaim mapClaim(io.r2dbc.spi.Readable row) {
        return new SettlementClaim(
                UUID.fromString(row.get("id", String.class)),
                UUID.fromString(row.get("run_id", String.class)),
                UUID.fromString(row.get("consume_operation_id", String.class)),
                row.get("account_id", String.class),
                row.get("feature", String.class),
                row.get("credits_cents_policy_version", String.class),
                row.get("actual_cents", Integer.class),
                UUID.fromString(row.get("claim_token", String.class)),
                row.get("attempt_count", Integer.class));
    }

    private static String truncate(String value) {
        String normalized = value == null || value.isBlank() ? "Unknown" : value;
        return normalized.length() <= MAX_ERROR_CODE_LENGTH
                ? normalized : normalized.substring(0, MAX_ERROR_CODE_LENGTH);
    }

    public record SettlementClaim(
            UUID id, UUID runId, UUID consumeOperationId,
            String accountId, String feature, String policyVersion,
            int actualCents, UUID claimToken, int attemptCount) {}
}
