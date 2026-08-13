package com.grassland.marketplace.taskcatalog;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 履约核验数据访问（草场 Verification v1，R2DBC 手写 SQL，house style）。
 *
 * <p>镜像 {@link SubmissionRepository} / {@link SubmissionAttachmentRepository}。keyed on submission_id：
 * 一份交付物一份核验记录，商家触发 / 重跑均 {@code ON CONFLICT (submission_id) DO UPDATE} 原地 upsert。
 *
 * <p>checks 存 jsonb：写用 {@code CAST(:checks AS jsonb)} 绑 JSON 字符串，读用 {@code checks::text}
 * （与 {@code OutboxRepository} 的 payload 同款）。
 */
@Component
public class EngagementVerificationRepository {

    private static final String SELECT_COLS =
            "id::text, submission_id::text, status, checks::text, latest_run_id::text, engine_version, "
            + "task_context_snapshot::text, evidence_snapshot::text, last_checked_at, created_at, updated_at";

    private final DatabaseClient db;

    public EngagementVerificationRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * 原地 upsert 一份交付物的核验记录（商家触发核验 / 重跑均走此）。{@code ON CONFLICT (submission_id)}
     * 命中既有行则更新 status/checks 并刷新 last_checked_at；否则插入新行。返回最新行。
     */
    public Mono<EngagementVerification> upsert(String submissionId, String status, String checksJson) {
        return db.sql("""
                INSERT INTO engagement_verification(id, submission_id, status, checks)
                VALUES (CAST(:id AS uuid), CAST(:sub AS uuid), :status, CAST(:checks AS jsonb))
                ON CONFLICT (submission_id) DO UPDATE
                    SET status = :status, checks = CAST(:checks AS jsonb),
                        last_checked_at = now(), updated_at = now()
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", UUID.randomUUID().toString())
                .bind("sub", submissionId)
                .bind("status", status)
                .bind("checks", checksJson)
                .map(EngagementVerificationRepository::map).one();
    }

    /** Verification v2: append an immutable run, then fold it into the backwards-compatible latest row. */
    public Mono<EngagementVerification> appendRun(
            String submissionId, String status, String checksJson,
            String taskContextJson, String evidenceJson, String triggeredBy) {
        String runId = UUID.randomUUID().toString();
        return db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:sub, 0))")
                .bind("sub", submissionId).then()
                .then(db.sql("""
                        INSERT INTO engagement_verification_run(
                            id, submission_id, run_number, engine_version, status,
                            task_context_snapshot, evidence_snapshot, checks, triggered_by)
                        SELECT CAST(:runId AS uuid), CAST(:sub AS uuid),
                               COALESCE(MAX(run_number), 0) + 1, 'v2', :status,
                               CAST(:context AS jsonb), CAST(:evidence AS jsonb), CAST(:checks AS jsonb),
                               CAST(:actor AS uuid)
                          FROM engagement_verification_run
                         WHERE submission_id = CAST(:sub AS uuid)
                        RETURNING id::text
                        """)
                        .bind("runId", runId).bind("sub", submissionId).bind("status", status)
                        .bind("context", taskContextJson).bind("evidence", evidenceJson)
                        .bind("checks", checksJson)
                        .bind("actor", triggeredBy == null
                                ? io.r2dbc.spi.Parameters.in(String.class)
                                : io.r2dbc.spi.Parameters.in(triggeredBy))
                        .map(row -> row.get("id", String.class)).one())
                .flatMap(savedRunId -> db.sql("""
                        INSERT INTO engagement_verification(
                            id, submission_id, status, checks, latest_run_id, engine_version,
                            task_context_snapshot, evidence_snapshot)
                        VALUES(CAST(:id AS uuid), CAST(:sub AS uuid), :status, CAST(:checks AS jsonb),
                               CAST(:runId AS uuid), 'v2', CAST(:context AS jsonb), CAST(:evidence AS jsonb))
                        ON CONFLICT (submission_id) DO UPDATE SET
                            status=:status, checks=CAST(:checks AS jsonb), latest_run_id=CAST(:runId AS uuid),
                            engine_version='v2', task_context_snapshot=CAST(:context AS jsonb),
                            evidence_snapshot=CAST(:evidence AS jsonb), last_checked_at=now(), updated_at=now()
                        RETURNING %s
                        """.formatted(SELECT_COLS))
                        .bind("id", UUID.randomUUID().toString()).bind("sub", submissionId)
                        .bind("status", status).bind("checks", checksJson).bind("runId", savedRunId)
                        .bind("context", taskContextJson).bind("evidence", evidenceJson)
                        .map(EngagementVerificationRepository::map).one());
    }

    /** 取一份交付物的核验记录（confirm 前置闸门、capture 安全网闸门、详情用）。无 → empty。 */
    public Mono<EngagementVerification> findBySubmission(String submissionId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM engagement_verification"
                + " WHERE submission_id = CAST(:sub AS uuid)")
                .bind("sub", submissionId)
                .map(EngagementVerificationRepository::map).one();
    }

    /**
     * 取一份交付物的**生效**核验状态（GL-P2-ADMIN-004）：有人工改判（verification_override）则 override 优先，
     * 否则回落自动结论（engagement_verification.status）。两者皆无 → empty。
     *
     * <p>供 confirm 闸门 / 结算阻断 / 运营队列三处统一调用，避免各自处理 override 逻辑。
     */
    public Mono<String> findEffectiveStatus(String submissionId) {
        return db.sql("""
                SELECT status FROM (
                    SELECT COALESCE(
                        (SELECT vo.status FROM verification_override vo
                         WHERE vo.submission_id = CAST(:sub AS uuid)),
                        (SELECT v.status FROM engagement_verification v
                         WHERE v.submission_id = CAST(:sub AS uuid))
                    ) AS status
                ) effective
                WHERE status IS NOT NULL
                """)
                .bind("sub", submissionId)
                .map(row -> row.get("status", String.class))
                .one();
    }

    /** 按 submission 批量取核验记录（商家查看交付物列表时，一次查全避免 N+1）。空入参 → 空 Flux。 */
    public Flux<EngagementVerification> findBySubmissions(List<String> submissionIds) {
        if (submissionIds.isEmpty()) {
            return Flux.empty();
        }
        StringBuilder sql = new StringBuilder("SELECT ").append(SELECT_COLS)
                .append(" FROM engagement_verification WHERE submission_id IN (");
        for (int i = 0; i < submissionIds.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append(":sid").append(i);
        }
        sql.append(')');
        GenericExecuteSpec spec = db.sql(sql.toString());
        for (int i = 0; i < submissionIds.size(); i++) {
            spec = spec.bind("sid" + i, UUID.fromString(submissionIds.get(i)));
        }
        return spec.map(EngagementVerificationRepository::map).all();
    }

    public Flux<EngagementVerificationRun> findRuns(String submissionId, int limit) {
        return db.sql("""
                SELECT id::text, submission_id::text, run_number, engine_version, status,
                       task_context_snapshot::text, evidence_snapshot::text, checks::text,
                       triggered_by::text, created_at
                  FROM engagement_verification_run
                 WHERE submission_id=CAST(:sub AS uuid)
                 ORDER BY run_number DESC LIMIT :limit
                """).bind("sub", submissionId).bind("limit", Math.max(1, Math.min(limit, 100)))
                .map(row -> new EngagementVerificationRun(
                        row.get("id", String.class), row.get("submission_id", String.class),
                        row.get("run_number", Integer.class), row.get("engine_version", String.class),
                        row.get("status", String.class), row.get("task_context_snapshot", String.class),
                        row.get("evidence_snapshot", String.class), row.get("checks", String.class),
                        row.get("triggered_by", String.class),
                        toInstant(row.get("created_at", OffsetDateTime.class))))
                .all();
    }

    private static EngagementVerification map(Readable row) {
        return new EngagementVerification(
                row.get("id", String.class),
                row.get("submission_id", String.class),
                row.get("status", String.class),
                row.get("checks", String.class),
                row.get("latest_run_id", String.class),
                row.get("engine_version", String.class),
                row.get("task_context_snapshot", String.class),
                row.get("evidence_snapshot", String.class),
                toInstant(row.get("last_checked_at", OffsetDateTime.class)),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class))
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
