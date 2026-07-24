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
                    + " reviewed_by_account_id::text, decided_at, created_at, updated_at";

    private final DatabaseClient db;

    public TaskApplicationRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 报名（status=pending）。note 可空。UNIQUE(task,recommender) 违例 → empty（调用方判 409「已报名」）。 */
    public Mono<TaskApplication> create(String taskId, String recommenderAccountId, String note) {
        String id = UUID.randomUUID().toString();
        var spec = db.sql("""
                INSERT INTO task_application(id, task_id, recommender_account_id, status, note)
                VALUES (CAST(:id AS uuid), CAST(:taskId AS uuid), CAST(:rec AS uuid), 'pending', :note)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("taskId", taskId).bind("rec", recommenderAccountId);
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

    /** 接受（4B 直连路径）：pending → accepted，记录操作商家 + decided_at。0 行（非 pending / 不属该 task）→ empty。 */
    public Mono<TaskApplication> accept(String id, String taskId, String reviewerAccountId) {
        return transition(id, taskId, ApplicationStatus.PENDING.dbValue(), ApplicationStatus.ACCEPTED.dbValue(),
                reviewerAccountId);
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

    /** 激活（Slice 4F Saga activate）：reserving → accepted。不重写 reviewer/decided_at（beginAcceptance 已记录）。
     *  0 行（非 reserving）→ empty（幂等：重试或已变迁）。 */
    public Mono<TaskApplication> acceptFromReserving(String id, String taskId) {
        return db.sql("""
                UPDATE task_application SET status = :status, updated_at = now()
                WHERE id = CAST(:id AS uuid)
                  AND task_id = CAST(:taskId AS uuid)
                  AND status = :from
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("taskId", taskId)
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
                toInstant(row.get("updated_at", OffsetDateTime.class))
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }
}
