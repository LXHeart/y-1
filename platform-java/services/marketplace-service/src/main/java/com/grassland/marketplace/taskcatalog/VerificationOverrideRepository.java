package com.grassland.marketplace.taskcatalog;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 人工改判记录数据访问（GL-P2-ADMIN-004）。
 *
 * <p>不改动 {@code engagement_verification}（自动核验聚合态，仍只由 {@code runVerificationChecks} 写）。
 * 本表承载运营对 inconclusive 项的人工改判（passed/failed）。读端经
 * {@link EngagementVerificationRepository#findEffectiveStatus} 优先读 override。
 *
 * <p>一份交付物至多一条 override（UNIQUE submission_id）；重判 upsert 覆盖。
 */
@Component
public class VerificationOverrideRepository {

    private static final String SELECT_COLS =
            "id::text, submission_id::text, status, reviewer_account_id::text, review_note, created_at, updated_at";

    private final DatabaseClient db;

    public VerificationOverrideRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 取一份交付物的人工改判。无 → empty（读端回落自动结论）。 */
    public Mono<VerificationOverride> findBySubmission(String submissionId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM verification_override"
                        + " WHERE submission_id = CAST(:sub AS uuid)")
                .bind("sub", submissionId)
                .map(VerificationOverrideRepository::map).one();
    }

    /** upsert 人工改判（重判覆盖）。返回最新行。 */
    public Mono<VerificationOverride> upsert(String submissionId, String status, String reviewerAccountId, String note) {
        var spec = db.sql("""
                INSERT INTO verification_override(submission_id, status, reviewer_account_id, review_note)
                VALUES (CAST(:sub AS uuid), :status, CAST(:reviewer AS uuid), :note)
                ON CONFLICT (submission_id) DO UPDATE
                    SET status = :status, reviewer_account_id = EXCLUDED.reviewer_account_id,
                        review_note = EXCLUDED.review_note, updated_at = now()
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("sub", submissionId)
                .bind("status", status)
                .bind("reviewer", reviewerAccountId);
        spec = bindNullable(spec, "note", note);
        return spec.map(VerificationOverrideRepository::map).one();
    }

    private static VerificationOverride map(Readable row) {
        return new VerificationOverride(
                row.get("id", String.class),
                row.get("submission_id", String.class),
                row.get("status", String.class),
                row.get("reviewer_account_id", String.class),
                row.get("review_note", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class)));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }
}
