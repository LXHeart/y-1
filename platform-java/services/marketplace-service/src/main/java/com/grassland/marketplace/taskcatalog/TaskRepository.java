package com.grassland.marketplace.taskcatalog;

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
 * task 数据访问（R2DBC {@link DatabaseClient} 手写 SQL，风格同 identity 各 repository）。草场 Epic 4 Slice 4A（4B 名额、4F 赏金）。
 */
@Component
public class TaskRepository {

    private static final String SELECT_COLS =
            "id::text, owner_account_id::text, organization_id::text, title, description, status,"
                    + " content_form, platform, max_slots, bounty_cents, created_at, updated_at";

    private final DatabaseClient db;

    public TaskRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 创建任务（status=published）。description/contentForm/platform/maxSlots/bountyCents 可空
     * （maxSlots=null=不限名额；bountyCents=null/0=非资金型任务，>0=资金型赏金，Slice 4F Saga reserve 金额）。 */
    public Mono<Task> create(String ownerAccountId, String organizationId, String title,
                             String description, String contentForm, String platform, Integer maxSlots,
                             Long bountyCents) {
        String id = UUID.randomUUID().toString();
        var spec = db.sql("""
                INSERT INTO task(id, owner_account_id, organization_id, title, description, status, content_form, platform, max_slots, bounty_cents)
                VALUES (CAST(:id AS uuid), CAST(:owner AS uuid), CAST(:org AS uuid), :title,
                        :desc, 'published', :contentForm, :platform, :maxSlots, :bountyCents)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("owner", ownerAccountId).bind("org", organizationId).bind("title", title);
        spec = bindNullable(spec, "desc", description);
        spec = bindNullable(spec, "contentForm", contentForm);
        spec = bindNullable(spec, "platform", platform);
        spec = bindNullableInt(spec, "maxSlots", maxSlots);
        spec = bindNullableLong(spec, "bountyCents", bountyCents);
        return spec.map(TaskRepository::map).one();
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

    /** 某 org 的活跃（非 closed）任务数——发布限额执行用（Slice 4B）。 */
    public Mono<Integer> countActiveByOrganization(String organizationId) {
        return db.sql("SELECT COUNT(*)::int AS c FROM task"
                + " WHERE organization_id = CAST(:org AS uuid) AND status <> 'closed'")
                .bind("org", organizationId)
                .map(r -> r.get("c", Integer.class)).one();
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
                toInstant(row.get("updated_at", OffsetDateTime.class))
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
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
}
