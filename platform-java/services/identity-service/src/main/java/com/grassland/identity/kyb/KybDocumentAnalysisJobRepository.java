package com.grassland.identity.kyb;

import java.time.Duration;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class KybDocumentAnalysisJobRepository {

    private final DatabaseClient db;

    public KybDocumentAnalysisJobRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Void> enqueue(UUID attachmentId) {
        return db.sql("""
                INSERT INTO kyb_document_analysis_job(attachment_id)
                VALUES (CAST(:attachmentId AS uuid))
                ON CONFLICT (attachment_id) DO NOTHING
                """)
                .bind("attachmentId", attachmentId)
                .then();
    }

    public Flux<Job> claimBatch(int limit, UUID claimToken, Duration lease) {
        return db.sql("""
                WITH candidates AS (
                    SELECT attachment_id FROM kyb_document_analysis_job
                    WHERE status = 'pending' AND next_attempt_at <= now()
                      AND (claimed_until IS NULL OR claimed_until <= now())
                    ORDER BY created_at, attachment_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT :limit
                )
                UPDATE kyb_document_analysis_job AS target
                SET claimed_until = now() + (:leaseMillis * interval '1 millisecond'),
                    claim_token = CAST(:claimToken AS uuid),
                    attempt_count = target.attempt_count + 1,
                    last_error_code = NULL
                FROM candidates
                WHERE target.attachment_id = candidates.attachment_id
                RETURNING target.attachment_id::text, target.claim_token::text, target.attempt_count
                """)
                .bind("limit", Math.max(limit, 1))
                .bind("leaseMillis", Math.max(lease.toMillis(), 1L))
                .bind("claimToken", claimToken)
                .map(row -> new Job(
                        UUID.fromString(row.get("attachment_id", String.class)),
                        UUID.fromString(row.get("claim_token", String.class)),
                        row.get("attempt_count", Integer.class)))
                .all();
    }

    public Mono<Boolean> complete(Job job, KybVerifiedDocument result) {
        return db.sql("""
                WITH claimed AS (
                    UPDATE kyb_document_analysis_job
                    SET status = 'completed', completed_at = now(), claimed_until = NULL,
                        claim_token = NULL, last_error_code = NULL
                    WHERE attachment_id = CAST(:attachmentId AS uuid)
                      AND claim_token = CAST(:claimToken AS uuid) AND status = 'pending'
                    RETURNING attachment_id
                )
                UPDATE merchant_attachment AS attachment
                SET ocr_status = :status, ocr_result = CAST(:result AS jsonb),
                    ocr_provider = :provider, ocr_model = :model,
                    ocr_result_version = :version, ocr_analyzed_at = now(), ocr_failure_code = NULL
                FROM claimed
                WHERE attachment.id = claimed.attachment_id
                """)
                .bind("attachmentId", job.attachmentId())
                .bind("claimToken", job.claimToken())
                .bind("status", result.status())
                .bind("result", result.safeResultJson())
                .bind("provider", result.provider())
                .bind("model", result.model())
                .bind("version", result.schemaVersion())
                .fetch().rowsUpdated().map(count -> count > 0).defaultIfEmpty(false);
    }

    public Mono<Boolean> retry(Job job, Duration delay, String errorCode, boolean dead) {
        return db.sql("""
                UPDATE kyb_document_analysis_job
                SET status = :status, claimed_until = NULL, claim_token = NULL,
                    next_attempt_at = now() + (:delayMillis * interval '1 millisecond'),
                    last_error_code = :errorCode
                WHERE attachment_id = CAST(:attachmentId AS uuid)
                  AND claim_token = CAST(:claimToken AS uuid) AND status = 'pending'
                """)
                .bind("status", dead ? "dead" : "pending")
                .bind("delayMillis", Math.max(delay.toMillis(), 1L))
                .bind("errorCode", normalize(errorCode))
                .bind("attachmentId", job.attachmentId())
                .bind("claimToken", job.claimToken())
                .fetch().rowsUpdated()
                .flatMap(count -> count > 0
                        ? markAttachmentFailure(job.attachmentId(), dead, normalize(errorCode)).thenReturn(true)
                        : Mono.just(false));
    }

    private Mono<Void> markAttachmentFailure(UUID attachmentId, boolean dead, String errorCode) {
        return db.sql("""
                UPDATE merchant_attachment
                SET ocr_status = :status, ocr_failure_code = :errorCode
                WHERE id = CAST(:attachmentId AS uuid)
                """)
                .bind("status", dead ? "failed" : "pending")
                .bind("errorCode", errorCode)
                .bind("attachmentId", attachmentId)
                .then();
    }

    private static String normalize(String value) {
        String normalized = value == null || value.isBlank() ? "ANALYSIS_FAILED" : value.trim();
        return normalized.substring(0, Math.min(normalized.length(), 64));
    }

    public record Job(UUID attachmentId, UUID claimToken, int attemptCount) {}
}

