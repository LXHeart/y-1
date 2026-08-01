package com.grassland.marketplace.taskcatalog;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * task 数据访问（R2DBC {@link DatabaseClient} 手写 SQL，风格同 identity 各 repository）。
 *
 * <p>草场 Epic 4 Slice 4A（4B 名额、4F 赏金）+ GL-P1-TASK-001 Stage 1 生命周期：
 * <ul>
 *   <li>{@link #create} 仍是 immediate-publish（{@code POST /api/tasks} 兼容路径）：落 published + published_at + v1 快照；</li>
 *   <li>{@link #createDraft}/{@link #updateDraft}/{@link #publish}/{@link #close}/{@link #cancel} 是生命周期命令，
 *       全部 guarded-UPDATE-with-RETURNING（{@code WHERE status=:from AND version=:expected}），version+1，0 行 → empty（调用方判 409）；</li>
 *   <li>{@link #publish} 同事务落 {@code task_version} 不可变快照（HLD §5.3）；</li>
 *   <li>{@link #countActiveByOrganization} 改 {@code status='published'}、{@link #countCreatedThisMonthByOrganization} 改按 {@code published_at}
 *       （draft 创建不占发布额度）。</li>
 * </ul>
 */
@Component
public class TaskRepository {

    private static final String SELECT_COLS =
            "id::text, owner_account_id::text, organization_id::text, title, description, status,"
                    + " content_form, platform, max_slots, bounty_cents, created_at, updated_at,"
                    + " version, application_deadline, published_at, cancelled_at";

    private final DatabaseClient db;

    public TaskRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * 创建即发布（兼容 {@code POST /api/tasks}）：status=published，published_at=now，落 v1 快照。
     * 由 controller 包进「INSERT task + INSERT task_version + outbox」同一 R2DBC 事务。
     */
    public Mono<Task> create(String ownerAccountId, String organizationId, String title,
                             String description, String contentForm, String platform, Integer maxSlots,
                             Long bountyCents, Instant applicationDeadline) {
        String id = UUID.randomUUID().toString();
        var spec = db.sql("""
                INSERT INTO task(id, owner_account_id, organization_id, title, description, status,
                                 content_form, platform, max_slots, bounty_cents, published_at, application_deadline)
                VALUES (CAST(:id AS uuid), CAST(:owner AS uuid), CAST(:org AS uuid), :title,
                        :desc, 'published', :contentForm, :platform, :maxSlots, :bountyCents,
                        now(), :deadline)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("owner", ownerAccountId).bind("org", organizationId).bind("title", title);
        spec = bindNullable(spec, "desc", description);
        spec = bindNullable(spec, "contentForm", contentForm);
        spec = bindNullable(spec, "platform", platform);
        spec = bindNullableInt(spec, "maxSlots", maxSlots);
        spec = bindNullableLong(spec, "bountyCents", bountyCents);
        spec = bindNullableDeadline(spec, "deadline", applicationDeadline);
        return spec.map(TaskRepository::map).one()
                .flatMap(task -> appendVersion(task, ownerAccountId).thenReturn(task));
    }

    /** 创建草稿（status=draft, version=0）。draft 创建不占发布额度、不需资金权限。 */
    public Mono<Task> createDraft(String ownerAccountId, String organizationId, String title,
                                  String description, String contentForm, String platform, Integer maxSlots,
                                  Long bountyCents, Instant applicationDeadline) {
        String id = UUID.randomUUID().toString();
        var spec = db.sql("""
                INSERT INTO task(id, owner_account_id, organization_id, title, description, status,
                                 content_form, platform, max_slots, bounty_cents, version, application_deadline)
                VALUES (CAST(:id AS uuid), CAST(:owner AS uuid), CAST(:org AS uuid), :title,
                        :desc, 'draft', :contentForm, :platform, :maxSlots, :bountyCents, 0, :deadline)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("owner", ownerAccountId).bind("org", organizationId).bind("title", title);
        spec = bindNullable(spec, "desc", description);
        spec = bindNullable(spec, "contentForm", contentForm);
        spec = bindNullable(spec, "platform", platform);
        spec = bindNullableInt(spec, "maxSlots", maxSlots);
        spec = bindNullableLong(spec, "bountyCents", bountyCents);
        spec = bindNullableDeadline(spec, "deadline", applicationDeadline);
        return spec.map(TaskRepository::map).one();
    }

    /** 编辑草稿字段（仅 draft 态；guarded by status+version，version+1）。0 行（非 draft / 版本冲突）→ empty。 */
    public Mono<Task> updateDraft(String id, int expectedVersion, String title, String description,
                                  String contentForm, String platform, Integer maxSlots, Long bountyCents,
                                  Instant applicationDeadline) {
        var spec = db.sql("""
                UPDATE task SET title = :title, description = :desc, content_form = :contentForm,
                                platform = :platform, max_slots = :maxSlots, bounty_cents = :bountyCents,
                                application_deadline = :deadline, version = version + 1, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'draft' AND version = :expected
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("expected", expectedVersion).bind("title", title);
        spec = bindNullable(spec, "desc", description);
        spec = bindNullable(spec, "contentForm", contentForm);
        spec = bindNullable(spec, "platform", platform);
        spec = bindNullableInt(spec, "maxSlots", maxSlots);
        spec = bindNullableLong(spec, "bountyCents", bountyCents);
        spec = bindNullableDeadline(spec, "deadline", applicationDeadline);
        return spec.map(TaskRepository::map).one();
    }

    /** 发布草稿（draft→published，published_at=now，version+1）+ 同事务落 task_version 快照。
     *  0 行（非 draft / 版本冲突）→ empty。快照取 RETURNING 的当前字段（已含编辑后的值）。 */
    public Mono<Task> publish(String id, int expectedVersion, String publishedBy) {
        return db.sql("""
                UPDATE task SET status = 'published', published_at = now(), version = version + 1, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'draft' AND version = :expected
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("expected", expectedVersion)
                .map(TaskRepository::map).one()
                .flatMap(task -> appendVersion(task, publishedBy).thenReturn(task));
    }

    /** 关闭报名（published→closed，version+1）。既有履约不受影响。0 行（非 published / 版本冲突）→ empty。 */
    public Mono<Task> close(String id, int expectedVersion) {
        return transition(id, expectedVersion, "published", "closed");
    }

    /** 取消任务（draft|published→cancelled，cancelled_at=now，version+1）。0 行（终态 / 版本冲突）→ empty。 */
    public Mono<Task> cancel(String id, int expectedVersion) {
        return db.sql("""
                UPDATE task SET status = 'cancelled', cancelled_at = now(), version = version + 1, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status IN ('draft', 'published') AND version = :expected
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("expected", expectedVersion)
                .map(TaskRepository::map).one();
    }

    public Mono<Task> findById(String id) {
        return db.sql("SELECT " + SELECT_COLS + " FROM task WHERE id = CAST(:id AS uuid)")
                .bind("id", id)
                .map(TaskRepository::map).one();
    }

    /** 列某 org 的任务；status 为空则不限（大厅默认查 published 由调用方传入）。 */
    public Flux<Task> findByOrganization(String organizationId, String status) {
        if (status == null || status.isBlank()) {
            return db.sql("SELECT " + SELECT_COLS
                    + " FROM task WHERE organization_id = CAST(:org AS uuid) ORDER BY created_at DESC")
                    .bind("org", organizationId)
                    .map(TaskRepository::map).all();
        }
        return db.sql("SELECT " + SELECT_COLS
                + " FROM task WHERE organization_id = CAST(:org AS uuid) AND status = :status ORDER BY created_at DESC")
                .bind("org", organizationId).bind("status", status)
                .map(TaskRepository::map).all();
    }

    /** 某 org 当前 published 的任务数——发布限额执行用（Stage 1：只 published 算活跃，draft/cancelled 不占）。 */
    public Mono<Integer> countActiveByOrganization(String organizationId) {
        return db.sql("SELECT COUNT(*)::int AS c FROM task"
                + " WHERE organization_id = CAST(:org AS uuid) AND status = 'published'")
                .bind("org", organizationId)
                .map(r -> r.get("c", Integer.class)).one();
    }

    /** 某 org <b>本自然月</b>发布的任务数（按 published_at；draft 创建不计）——月度发布限额执行用（D-05）。
     *  月份边界按 DB 时区 {@code date_trunc('month', now())}；跨月自动重置。 */
    public Mono<Integer> countCreatedThisMonthByOrganization(String organizationId) {
        return db.sql("SELECT COUNT(*)::int AS c FROM task"
                + " WHERE organization_id = CAST(:org AS uuid) AND published_at IS NOT NULL"
                + " AND published_at >= date_trunc('month', now())")
                .bind("org", organizationId)
                .map(r -> r.get("c", Integer.class)).one();
    }

    /** 落一行不可变 task_version 快照（HLD §5.3）。version 取 task 当前 version（publish 后已 +1）。 */
    private Mono<Void> appendVersion(Task task, String publishedBy) {
        var spec = db.sql("""
                INSERT INTO task_version(task_id, version, title, description, content_form, platform,
                                         max_slots, bounty_cents, application_deadline, published_at, published_by)
                VALUES (CAST(:taskId AS uuid), :version, :title, :desc, :contentForm, :platform,
                        :maxSlots, :bountyCents, :deadline, COALESCE(:publishedAt, now()), CAST(:publishedBy AS uuid))
                """)
                .bind("taskId", task.id()).bind("version", task.version()).bind("title", task.title());
        spec = bindNullable(spec, "publishedBy", publishedBy);
        spec = bindNullable(spec, "desc", task.description());
        spec = bindNullable(spec, "contentForm", task.contentForm());
        spec = bindNullable(spec, "platform", task.platform());
        spec = bindNullableInt(spec, "maxSlots", task.maxSlots());
        spec = bindNullableLong(spec, "bountyCents", task.bountyCents());
        spec = bindNullableDeadline(spec, "deadline", task.applicationDeadline());
        spec = bindNullableDeadline(spec, "publishedAt", task.publishedAt());
        return spec.then();
    }

    private Mono<Task> transition(String id, int expectedVersion, String fromStatus, String toStatus) {
        return db.sql("""
                UPDATE task SET status = :status, version = version + 1, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = :from AND version = :expected
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("expected", expectedVersion)
                .bind("from", fromStatus).bind("status", toStatus)
                .map(TaskRepository::map).one();
    }

    private static Task map(Readable row) {
        return new Task(
                row.get("id", String.class),
                row.get("owner_account_id", String.class),
                row.get("organization_id", String.class),
                row.get("title", String.class),
                row.get("description", String.class),
                row.get("status", String.class),
                row.get("content_form", String.class),
                row.get("platform", String.class),
                row.get("max_slots", Integer.class),
                row.get("bounty_cents", Long.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class)),
                value(row.get("version", Integer.class), 1),
                toInstant(row.get("application_deadline", OffsetDateTime.class)),
                toInstant(row.get("published_at", OffsetDateTime.class)),
                toInstant(row.get("cancelled_at", OffsetDateTime.class))
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static int value(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static GenericExecuteSpec bindNullableInt(GenericExecuteSpec spec, String name, Integer value) {
        return value == null ? spec.bindNull(name, Integer.class) : spec.bind(name, value);
    }

    private static GenericExecuteSpec bindNullableLong(GenericExecuteSpec spec, String name, Long value) {
        return value == null ? spec.bindNull(name, Long.class) : spec.bind(name, value);
    }

    private static GenericExecuteSpec bindNullableDeadline(GenericExecuteSpec spec, String name, Instant value) {
        return value == null
                ? spec.bindNull(name, OffsetDateTime.class)
                : spec.bind(name, value.atOffset(ZoneOffset.UTC));
    }
}
