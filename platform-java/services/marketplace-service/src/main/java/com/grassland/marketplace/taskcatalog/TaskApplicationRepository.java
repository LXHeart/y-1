package com.grassland.marketplace.taskcatalog;

import io.r2dbc.spi.R2dbcDataIntegrityViolationException;
import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * task_application 数据访问（R2DBC {@link DatabaseClient} 手写 SQL，风格同 {@link TaskRepository}）。草场 Epic 4 Slice 4B（4F 资金预留中间态）。
 *
 * <p>状态变更用条件 UPDATE（{@code status = :from} 守卫，泛化自 4B 的硬编码 {@code 'pending'}）+ {@code RETURNING}：
 * 0 行 → {@link Mono#empty()}（调用方据此判 409「已处理」或幂等跳过）。4F 新增 reserving 流转：
 * {@code beginAcceptance}（pending→reserving）、{@code acceptFromReserving}（reserving→accepted，不重写 reviewer）、
 * {@code revertReserving}（reserving→pending 补偿，清空 reviewer/decided_at 回可重试态）。并发名额计数放 Java 层（见 ApplicationController）。
 */
@Component
public class TaskApplicationRepository {

    private static final String SELECT_COLS =
            "id::text, task_id::text, recommender_account_id::text, status, note,"
                    + " reviewed_by_account_id::text, decided_at, created_at, updated_at, confirmed_at, bounty_cents,"
                    + " merchant_confirm_deadline_at, auto_confirmed_at, merchant_rejected_at, rejection_reason,"
                    + " merchant_rejection_dispute_id::text";

    private final DatabaseClient db;

    public TaskApplicationRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * 报名（status=pending）。note 可空。{@code bountyCents} = 报名时 task 赏金（provisional；accept 时才冻结）。
     * UNIQUE(task,recommender) 违例 → empty（调用方判 409「已报名」）。
     */
    public Mono<TaskApplication> create(String taskId, String recommenderAccountId, String note, long bountyCents) {
        String id = UUID.randomUUID().toString();
        var spec = db.sql("""
                INSERT INTO task_application(id, task_id, recommender_account_id, status, note, bounty_cents)
                VALUES (CAST(:id AS uuid), CAST(:taskId AS uuid), CAST(:rec AS uuid), 'pending', :note, :bounty)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("taskId", taskId).bind("rec", recommenderAccountId).bind("bounty", bountyCents);
        spec = bindNullable(spec, "note", note);
        return spec.map(TaskApplicationRepository::map).one()
                .onErrorResume(R2dbcDataIntegrityViolationException.class, e -> Mono.empty());
    }

    public Mono<TaskApplication> findById(String id) {
        return db.sql("SELECT " + SELECT_COLS + " FROM task_application WHERE id = CAST(:id AS uuid)")
                .bind("id", id)
                .map(TaskApplicationRepository::map).one();
    }

    /** 某 recommender 在某 task 的现存报名（去重预查；UNIQUE 保证至多一行）。 */
    public Mono<TaskApplication> findByTaskAndRecommender(String taskId, String recommenderAccountId) {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM task_application WHERE task_id = CAST(:taskId AS uuid)"
                + " AND recommender_account_id = CAST(:rec AS uuid)")
                .bind("taskId", taskId).bind("rec", recommenderAccountId)
                .map(TaskApplicationRepository::map).one();
    }

    public Flux<TaskApplication> findByTaskId(String taskId) {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM task_application WHERE task_id = CAST(:taskId AS uuid) ORDER BY created_at")
                .bind("taskId", taskId)
                .map(TaskApplicationRepository::map).all();
    }

    /**
     * 接受（4B 直连路径）：pending → accepted，记录操作商家 + decided_at，<b>并冻结 accept 时赏金到 bounty_cents</b>
     * （snapshot-pinning：此后结算读这列而非可变 task 行）。0 行（非 pending / 不属该 task）→ empty。
     */
    public Mono<TaskApplication> accept(String id, String taskId, String reviewerAccountId, long bountyCents) {
        return db.sql("""
                UPDATE task_application
                SET status = :status, reviewed_by_account_id = CAST(:reviewer AS uuid),
                    decided_at = now(), bounty_cents = :bounty, updated_at = now()
                WHERE id = CAST(:id AS uuid)
                  AND task_id = CAST(:taskId AS uuid)
                  AND status = :from
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("taskId", taskId)
                .bind("from", ApplicationStatus.PENDING.dbValue())
                .bind("status", ApplicationStatus.ACCEPTED.dbValue())
                .bind("reviewer", reviewerAccountId).bind("bounty", bountyCents)
                .map(TaskApplicationRepository::map).one();
    }

    /** 结算确认（Slice 5A）：accepted + 未确认 → 设 confirmed_at（商家 ConfirmEngagement）。0 行（非 accepted / 已确认）→ empty。
     *  商家身份由上游 loadOwnedTask 校验为 task owner，故不另存 confirmed_by。
     *  <p>D-03：窗口到期自动结算复用本方法（{@code ConfirmationActivityImpl} 调）——条件 {@code confirmed_at IS NULL}
     *  保证「商家先确认 vs 自动结算」竞态只有一方落 confirmed_at，另一方 0 行→abort，无双结算。 */
    public Mono<TaskApplication> confirm(String id, String taskId) {
        return db.sql("""
                UPDATE task_application SET confirmed_at = now(), updated_at = now()
                WHERE id = CAST(:id AS uuid)
                  AND task_id = CAST(:taskId AS uuid)
                  AND status = 'accepted'
                  AND confirmed_at IS NULL
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("taskId", taskId)
                .map(TaskApplicationRepository::map).one();
    }

    /**
     * 商家拒绝系统核实通过的履约（D-03 §2）：accepted + 未手动确认/未拒绝 → 设 confirmed_at（系统核实事实成立，
     * 供争议终局 reconciliation 落钱）+ merchant_rejected_at/reason。若 Timer 在 trust 开案后抢先自动确认，
     * {@code auto_confirmed_at IS NOT NULL} 也允许 contest 接管；普通商家手动确认仍不可反悔。0 行 = 状态已变/重复操作。
     */
    public Mono<TaskApplication> contest(String id, String taskId, String disputeId, String reason) {
        GenericExecuteSpec spec = db.sql("""
                UPDATE task_application
                SET confirmed_at = COALESCE(confirmed_at, now()), merchant_rejected_at = now(), rejection_reason = :reason,
                    merchant_rejection_dispute_id = CAST(:dispute AS uuid), updated_at = now()
                WHERE id = CAST(:id AS uuid)
                  AND task_id = CAST(:taskId AS uuid)
                  AND status = 'accepted'
                  AND (confirmed_at IS NULL OR auto_confirmed_at IS NOT NULL)
                  AND merchant_rejected_at IS NULL
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("taskId", taskId).bind("dispute", disputeId);
        spec = bindNullable(spec, "reason", reason);
        return spec.map(TaskApplicationRepository::map).one();
    }

    /** 窗口到期自动确认（D-03）：accepted + 未确认 → 同时设 confirmed_at / auto_confirmed_at。
     *  0 行 = 商家先手动确认 / 非 accepted。{@code auto_confirmed_at} 支撑 activity 崩溃重试：重试见它非空可继续 capture，
     *  仅 confirmed_at 非空则说明商家先确认，本 workflow abort。 */
    public Mono<TaskApplication> autoConfirm(String id, String taskId) {
        return db.sql("""
                UPDATE task_application
                SET confirmed_at = now(), auto_confirmed_at = now(), updated_at = now()
                WHERE id = CAST(:id AS uuid)
                  AND task_id = CAST(:taskId AS uuid)
                  AND status = 'accepted'
                  AND confirmed_at IS NULL
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("taskId", taskId)
                .map(TaskApplicationRepository::map).one();
    }

    /** 设商家确认窗口截止（D-03）：推荐官提交履约时调，deadline = now() + windowSeconds（DB 算，避免绑时间戳）。
     *  0 行（属该 task 的报名不存在）→ empty。 */
    public Mono<TaskApplication> setConfirmDeadline(String id, String taskId, long windowSeconds) {
        return db.sql("""
                UPDATE task_application
                SET merchant_confirm_deadline_at = now() + (:seconds * interval '1 second'), updated_at = now()
                WHERE id = CAST(:id AS uuid)
                  AND task_id = CAST(:taskId AS uuid)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("taskId", taskId).bind("seconds", Math.max(0, windowSeconds))
                .map(TaskApplicationRepository::map).one();
    }

    /** 拒绝：pending → rejected。 */
    public Mono<TaskApplication> reject(String id, String taskId, String reviewerAccountId) {
        return transition(id, taskId, ApplicationStatus.PENDING.dbValue(), ApplicationStatus.REJECTED.dbValue(),
                reviewerAccountId);
    }

    /** 开始接受（Slice 4F Saga beginAcceptance）：pending → reserving，记录操作商家 + decided_at。0 行 → empty。 */
    public Mono<TaskApplication> beginAcceptance(String id, String taskId, String reviewerAccountId) {
        return transition(id, taskId, ApplicationStatus.PENDING.dbValue(), ApplicationStatus.RESERVING.dbValue(),
                reviewerAccountId);
    }

    /** 激活（Slice 4F Saga activate）：reserving → accepted，<b>冻结 accept 时赏金</b>（snapshot-pinning）。
     *  不重写 reviewer/decided_at（beginAcceptance 已记录）。0 行（非 reserving）→ empty（幂等：重试或已变迁）。 */
    public Mono<TaskApplication> acceptFromReserving(String id, String taskId, long bountyCents) {
        return db.sql("""
                UPDATE task_application SET status = :status, bounty_cents = :bounty, updated_at = now()
                WHERE id = CAST(:id AS uuid)
                  AND task_id = CAST(:taskId AS uuid)
                  AND status = :from
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("taskId", taskId).bind("bounty", bountyCents)
                .bind("from", ApplicationStatus.RESERVING.dbValue())
                .bind("status", ApplicationStatus.ACCEPTED.dbValue())
                .map(TaskApplicationRepository::map).one();
    }

    /** 补偿回退（Slice 4F Saga compensate）：reserving → pending，清空 reviewer/decided_at（回可重试态）。
     *  0 行（非 reserving）→ empty（幂等：重试或已回退）。 */
    public Mono<TaskApplication> revertReserving(String id, String taskId) {
        return db.sql("""
                UPDATE task_application
                SET status = :status, reviewed_by_account_id = NULL, decided_at = NULL, updated_at = now()
                WHERE id = CAST(:id AS uuid)
                  AND task_id = CAST(:taskId AS uuid)
                  AND status = :from
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("taskId", taskId)
                .bind("from", ApplicationStatus.RESERVING.dbValue())
                .bind("status", ApplicationStatus.PENDING.dbValue())
                .map(TaskApplicationRepository::map).one();
    }

    /** 撤销：本人 pending → withdrawn（无 reviewer）。WHERE 含 recommender 即资源级自查（HLD 7.4）。 */
    public Mono<TaskApplication> withdraw(String id, String taskId, String recommenderAccountId) {
        return db.sql("""
                UPDATE task_application SET status = 'withdrawn', updated_at = now()
                WHERE id = CAST(:id AS uuid)
                  AND task_id = CAST(:taskId AS uuid)
                  AND recommender_account_id = CAST(:rec AS uuid)
                  AND status = 'pending'
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("taskId", taskId).bind("rec", recommenderAccountId)
                .map(TaskApplicationRepository::map).one();
    }

    /** 已被接受的名额计数（名额控制用）。 */
    public Mono<Integer> countAcceptedByTask(String taskId) {
        return db.sql("SELECT COUNT(*)::int AS c FROM task_application"
                + " WHERE task_id = CAST(:taskId AS uuid) AND status = 'accepted'")
                .bind("taskId", taskId)
                .map(r -> r.get("c", Integer.class)).one();
    }

    /**
     * D-03 §5：某任务下「已 accept 但未提交凭证」的报名（商家 cancel 时全额返还商家；已提交/核实的照常结算）。
     * NOT EXISTS 子查询排除有 engagement_submission 的报名。
     */
    public Flux<TaskApplication> findAcceptedByTaskWithoutSubmission(String taskId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM task_application a"
                + " WHERE task_id = CAST(:taskId AS uuid) AND status = 'accepted'"
                + " AND NOT EXISTS (SELECT 1 FROM engagement_submission s WHERE s.application_id = a.id)")
                .bind("taskId", taskId)
                .map(TaskApplicationRepository::map).all();
    }

    private Mono<TaskApplication> transition(String id, String taskId, String fromStatus, String toStatus,
                                             String reviewerAccountId) {
        return db.sql("""
                UPDATE task_application
                SET status = :status, reviewed_by_account_id = CAST(:reviewer AS uuid),
                    decided_at = now(), updated_at = now()
                WHERE id = CAST(:id AS uuid)
                  AND task_id = CAST(:taskId AS uuid)
                  AND status = :from
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("taskId", taskId).bind("from", fromStatus)
                .bind("status", toStatus).bind("reviewer", reviewerAccountId)
                .map(TaskApplicationRepository::map).one();
    }

    private static TaskApplication map(Readable row) {
        return new TaskApplication(
                row.get("id", String.class),
                row.get("task_id", String.class),
                row.get("recommender_account_id", String.class),
                row.get("status", String.class),
                row.get("note", String.class),
                row.get("reviewed_by_account_id", String.class),
                toInstant(row.get("decided_at", OffsetDateTime.class)),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class)),
                toInstant(row.get("confirmed_at", OffsetDateTime.class)),
                longValue(row.get("bounty_cents", Long.class)),
                toInstant(row.get("merchant_confirm_deadline_at", OffsetDateTime.class)),
                toInstant(row.get("auto_confirmed_at", OffsetDateTime.class)),
                toInstant(row.get("merchant_rejected_at", OffsetDateTime.class)),
                row.get("rejection_reason", String.class),
                row.get("merchant_rejection_dispute_id", String.class)
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static long longValue(Long raw) {
        return raw == null ? 0L : raw;
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }
}
