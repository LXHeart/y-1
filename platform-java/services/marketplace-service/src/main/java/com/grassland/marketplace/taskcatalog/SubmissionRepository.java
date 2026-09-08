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
                    + " review_note, reviewed_at, created_at, confirmation_workflow_started_at, platform_handle,"
                    + " comment_text, submission_kind";

    private final DatabaseClient db;

    public SubmissionRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * 提交交付物。已有待核验的一份时触发 partial unique 冲突 → empty（调用方转 409）。
     *
     * <p>D-03 cancel 并发守卫：INSERT 前 JOIN task 并 {@code FOR SHARE OF t}。它与 cancel 的 task UPDATE
     * （NO KEY UPDATE 锁）冲突，保证二者串行：提交先拿锁→cancel 等提交落库后看见 submission 不退款；
     * cancel 先拿锁→提交醒来见 cancelled 后 0 行，不会发生「已退款又补交」。
     */
    public Mono<EngagementSubmission> create(String applicationId, String recommenderAccountId,
                                             String contentUrl, String note, String platformHandle) {
        return create(applicationId, recommenderAccountId, contentUrl, note, platformHandle, null);
    }

    public Mono<EngagementSubmission> create(String applicationId, String recommenderAccountId,
                                             String contentUrl, String note, String platformHandle,
                                             String commentText) {
        var spec = db.sql("""
                WITH eligible AS (
                    SELECT a.id
                    FROM task_application a
                    JOIN task t ON t.id = a.task_id
                    WHERE a.id = CAST(:app AS uuid)
                      AND a.recommender_account_id = CAST(:rec AS uuid)
                      AND a.status = 'accepted'
                      AND a.confirmed_at IS NULL
                      AND a.contest_requested_at IS NULL
                      AND (t.status <> 'cancelled' OR EXISTS (
                          SELECT 1 FROM engagement_submission previous
                          WHERE previous.application_id = a.id AND previous.created_at <= t.cancelled_at
                      ))
                    FOR SHARE OF t
                )
                INSERT INTO engagement_submission(id, application_id, recommender_account_id, content_url, note,
                                                 platform_handle, comment_text, submission_kind)
                SELECT CAST(:id AS uuid), CAST(:app AS uuid), CAST(:rec AS uuid), :url, :note, :handle, :comment, 'published'
                FROM eligible
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", UUID.randomUUID().toString())
                .bind("app", applicationId).bind("rec", recommenderAccountId).bind("url", contentUrl);
        spec = bindNullable(spec, "note", note);
        spec = bindNullable(spec, "handle", platformHandle);
        spec = bindNullable(spec, "comment", commentText);
        return spec.map(SubmissionRepository::map).one()
                .onErrorResume(DataIntegrityViolationException.class, e -> Mono.empty());
    }

    /** 兼容 V41 之前的两参重载（既有测试）；无平台账号标识。 */
    public Mono<EngagementSubmission> create(String applicationId, String recommenderAccountId,
                                             String contentUrl, String note) {
        return create(applicationId, recommenderAccountId, contentUrl, note, null, null);
    }

    public Mono<EngagementSubmission> findById(String submissionId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM engagement_submission WHERE id = CAST(:id AS uuid)")
                .bind("id", submissionId)
                .map(SubmissionRepository::map).one();
    }

    /** D-03：扫描已提交但 confirmation workflow 尚未标记启动的行（DB commit→Temporal start 间隙补偿）。 */
    public Flux<EngagementSubmission> findConfirmationDispatchable(int limit) {
        return db.sql("SELECT " + SELECT_COLS + " FROM engagement_submission"
                        + " WHERE status = 'submitted' AND confirmation_workflow_started_at IS NULL"
                        + " ORDER BY created_at LIMIT :limit")
                .bind("limit", Math.max(1, limit))
                .map(SubmissionRepository::map).all();
    }

    /** start 成功或 WorkflowExecutionAlreadyStarted 后标记；guarded 防被退回/已处理行误标。 */
    public Mono<Boolean> markConfirmationWorkflowStarted(String submissionId) {
        return db.sql("""
                UPDATE engagement_submission SET confirmation_workflow_started_at = now(), updated_at = now()
                WHERE id = CAST(:id AS uuid)
                  AND status = 'submitted'
                  AND confirmation_workflow_started_at IS NULL
                """)
                .bind("id", submissionId)
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    public Flux<EngagementSubmission> findByApplication(String applicationId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM engagement_submission"
                + " WHERE application_id = CAST(:app AS uuid) ORDER BY created_at DESC")
                .bind("app", applicationId)
                .map(SubmissionRepository::map).all();
    }

    /** D-03 规则 4：该履约累计被退回（要求补证）的次数 = status='rejected' 的交付物行数。供商家退回上限校验。 */
    public Mono<Integer> countRejectedByApplication(String applicationId) {
        return db.sql("SELECT COUNT(*)::int AS c FROM engagement_submission"
                + " WHERE application_id = CAST(:app AS uuid) AND status = 'rejected'")
                .bind("app", applicationId)
                .map(r -> r.get("c", Integer.class)).one();
    }

    /** 当前待核验的交付物（confirm 守卫用）。 */
    public Mono<EngagementSubmission> findPending(String applicationId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM engagement_submission"
                + " WHERE application_id = CAST(:app AS uuid) AND status = 'submitted'"
                + " AND submission_kind = 'published'")
                .bind("app", applicationId)
                .map(SubmissionRepository::map).one();
    }

    // ---------- 任务书 #96 C96-04：发布前审稿（草稿行） ----------

    /**
     * 草稿送审行创建（kind=draft；content_url 允许空 = 不要求公开链接，TC96-015）。复用
     * uq_submission_pending 唯一位（同报名同时只有一份待审草稿或待核凭证），冲突 → empty（409）。
     */
    public Mono<EngagementSubmission> createDraft(String applicationId, String recommenderAccountId, String note) {
        return db.sql("""
                WITH eligible AS (
                    SELECT a.id FROM task_application a
                    WHERE a.id = CAST(:app AS uuid)
                      AND a.recommender_account_id = CAST(:rec AS uuid)
                      AND a.status = 'accepted' AND a.confirmed_at IS NULL
                )
                INSERT INTO engagement_submission(id, application_id, recommender_account_id, content_url, note,
                                                 submission_kind)
                SELECT CAST(:id AS uuid), CAST(:app AS uuid), CAST(:rec AS uuid), '', :note, 'draft'
                FROM eligible
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", UUID.randomUUID().toString())
                .bind("app", applicationId).bind("rec", recommenderAccountId)
                .bind("note", note == null || note.isBlank() ? null : note.trim())
                .map(SubmissionRepository::map).one()
                .onErrorResume(org.springframework.dao.DataIntegrityViolationException.class, e -> Mono.empty());
    }

    /** 最近一条被退回的草稿（补交期限按其 reviewed_at + 窗口派生）。 */
    public Mono<EngagementSubmission> findLatestRejectedDraft(String applicationId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM engagement_submission"
                        + " WHERE application_id = CAST(:app AS uuid) AND submission_kind = 'draft'"
                        + " AND status = 'rejected' ORDER BY reviewed_at DESC NULLS LAST, created_at DESC LIMIT 1")
                .bind("app", applicationId).map(SubmissionRepository::map).one();
    }

    /** 是否已有获批（accepted）草稿——审稿闸门放行条件。 */
    public Mono<Boolean> hasApprovedDraft(String applicationId) {
        return db.sql("SELECT EXISTS(SELECT 1 FROM engagement_submission"
                        + " WHERE application_id = CAST(:app AS uuid) AND submission_kind = 'draft'"
                        + " AND status = 'accepted') AS ok")
                .bind("app", applicationId).map(r -> Boolean.TRUE.equals(r.get("ok", Boolean.class))).one()
                .defaultIfEmpty(false);
    }

    /** 审稿超时扫描：待审草稿且超过窗口（派发器提醒/转人工）。 */
    public Flux<EngagementSubmission> findDraftReviewOverdue(int limit, long windowSeconds) {
        return db.sql("SELECT " + SELECT_COLS + " FROM engagement_submission"
                        + " WHERE submission_kind = 'draft' AND status = 'submitted'"
                        + " AND created_at <= now() - (:seconds * interval '1 second')"
                        + " ORDER BY created_at LIMIT :limit")
                .bind("seconds", Math.max(1, windowSeconds)).bind("limit", Math.max(1, limit))
                .map(SubmissionRepository::map).all();
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
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("confirmation_workflow_started_at", OffsetDateTime.class)),
                row.get("platform_handle", String.class),
                row.get("comment_text", String.class),
                row.get("submission_kind", String.class)
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }
}
