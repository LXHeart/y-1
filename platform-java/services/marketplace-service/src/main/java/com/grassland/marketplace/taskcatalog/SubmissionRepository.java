package com.grassland.marketplace.taskcatalog;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 履约交付物数据访问（R2DBC 手写 SQL，house style）。
 *
 * <p>状态迁移一律 guarded UPDATE（{@code WHERE status='submitted'}）→ 返回 empty 即「已被处理」，
 * 避免读-改-写竞态（商家点确认与点退回同时发生时只有一个赢家）。
 */
@Component
public class SubmissionRepository {

    private static final String SELECT_COLS =
            "id::text, application_id::text, recommender_account_id::text, content_url, note, status,"
                    + " review_note, reviewed_at, created_at";

    private final DatabaseClient db;

    public SubmissionRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 提交交付物。已有待核验的一份时触发 partial unique 冲突 → empty（调用方转 409）。 */
    public Mono<EngagementSubmission> create(String applicationId, String recommenderAccountId,
                                             String contentUrl, String note) {
        var spec = db.sql("""
                INSERT INTO engagement_submission(id, application_id, recommender_account_id, content_url, note)
                VALUES (CAST(:id AS uuid), CAST(:app AS uuid), CAST(:rec AS uuid), :url, :note)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", UUID.randomUUID().toString())
                .bind("app", applicationId).bind("rec", recommenderAccountId).bind("url", contentUrl);
        spec = bindNullable(spec, "note", note);
        return spec.map(SubmissionRepository::map).one()
                .onErrorResume(DataIntegrityViolationException.class, e -> Mono.empty());
    }

    public Flux<EngagementSubmission> findByApplication(String applicationId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM engagement_submission"
                + " WHERE application_id = CAST(:app AS uuid) ORDER BY created_at DESC")
                .bind("app", applicationId)
                .map(SubmissionRepository::map).all();
    }

    /** 当前待核验的交付物（confirm 守卫用）。 */
    public Mono<EngagementSubmission> findPending(String applicationId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM engagement_submission"
                + " WHERE application_id = CAST(:app AS uuid) AND status = 'submitted'")
                .bind("app", applicationId)
                .map(SubmissionRepository::map).one();
    }

    /** submitted → 指定终态（accepted / rejected），带审核备注。0 行（已被处理）→ empty。 */
    public Mono<EngagementSubmission> review(String submissionId, SubmissionStatus target, String reviewNote) {
        var spec = db.sql("""
                UPDATE engagement_submission
                SET status = :status, review_note = :note, reviewed_at = now(), updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'submitted'
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", submissionId).bind("status", target.dbValue());
        spec = bindNullable(spec, "note", reviewNote);
        return spec.map(SubmissionRepository::map).one();
    }

    private static EngagementSubmission map(Readable row) {
        return new EngagementSubmission(
                row.get("id", String.class),
                row.get("application_id", String.class),
                row.get("recommender_account_id", String.class),
                row.get("content_url", String.class),
                row.get("note", String.class),
                row.get("status", String.class),
                row.get("review_note", String.class),
                toInstant(row.get("reviewed_at", OffsetDateTime.class)),
                toInstant(row.get("created_at", OffsetDateTime.class))
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }
}
