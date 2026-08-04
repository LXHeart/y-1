package com.grassland.intelligence.ai.controlplane;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.Parameter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 平台模型配置仓储（model-control-plane，GL-P3-AI-001）。
 *
 * <p>版本化写入：{@link #create} 插入 version=1；{@link #revise} 在同事务内 disable 旧版本 + 插入新版本
 * （version=旧+1）+ 落 history。{@code (capability, model_role)} 的部分唯一索引（{@code WHERE enabled=true}）
 * 保证「同时只有一个 enabled 行」——disable 在 insert 之前执行，约束方可放行新行。
 */
@Component
public class PlatformModelConfigRepository {

    private static final String SELECT_COLS =
            "id::text, capability, model_role, provider, model, base_url, max_concurrency, "
                    + "health_status, enabled, version, updated_by, created_at, updated_at";

    private final DatabaseClient db;

    public PlatformModelConfigRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 当前有效的某个 (capability, model_role) 配置；无则空。 */
    public Mono<PlatformModelConfig> findCurrent(String capability, String modelRole) {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM platform_model_config"
                + " WHERE capability = :capability AND model_role = :modelRole AND enabled = true")
                .bind("capability", capability)
                .bind("modelRole", modelRole)
                .map(PlatformModelConfigRepository::map)
                .one();
    }

    /** 某能力的全部当前有效配置（primary + backup）。 */
    public Flux<PlatformModelConfig> findCurrentByCapability(String capability) {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM platform_model_config"
                + " WHERE capability = :capability AND enabled = true")
                .bind("capability", capability)
                .map(PlatformModelConfigRepository::map)
                .all();
    }

    /** 列出所有当前有效配置（admin 看板）。 */
    public Flux<PlatformModelConfig> findAllCurrent() {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM platform_model_config"
                + " WHERE enabled = true"
                + " ORDER BY capability, model_role")
                .map(PlatformModelConfigRepository::map)
                .all();
    }

    /** 行数（启动期 seed 判空用）。 */
    public Mono<Long> count() {
        return db.sql("SELECT COUNT(*) AS n FROM platform_model_config")
                .map((r, m) -> r.get("n", Long.class))
                .one();
    }

    /**
     * 创建 version=1（假定该 (capability, model_role) 当前无 enabled 行）。同事务落 history(create)。
     * 调用方负责在 {@code TransactionalOperator} 内执行，并在前置检查中确认不存在。
     */
    public Mono<UUID> create(PlatformModelConfig c, String adminId) {
        return db.sql("""
                INSERT INTO platform_model_config(
                    capability, model_role, provider, model, base_url, max_concurrency,
                    health_status, enabled, version, updated_by
                ) VALUES (
                    :capability, :modelRole, :provider, :model, :baseUrl, :maxConcurrency,
                    :healthStatus, true, 1, :adminId
                )
                RETURNING id::text
                """)
                .bind("capability", c.capability())
                .bind("modelRole", c.modelRole())
                .bind("provider", c.provider())
                .bind("model", c.model())
                .bind("baseUrl", c.baseUrl())
                .bind("maxConcurrency", Parameter.fromOrEmpty(c.maxConcurrency(), Integer.class))
                .bind("healthStatus", c.healthStatus())
                .bind("adminId", Parameter.fromOrEmpty(adminId, String.class))
                .map((r, m) -> r.get("id", String.class))
                .one()
                .map(UUID::fromString)
                .flatMap(id -> insertHistory(id, c, 1, "create", adminId).thenReturn(id));
    }

    /**
     * 修订：disable 旧 enabled 行 + 插入新版本（version=旧+1, enabled=true）+ history(update)。
     * 必须在事务内调用。旧行不存在时返回空（调用方转 404）。
     */
    public Mono<PlatformModelConfig> revise(String capability, String modelRole, PlatformModelConfig next, String adminId) {
        return findCurrent(capability, modelRole)
                .flatMap(current -> disable(current.id(), adminId)
                        .then(db.sql("""
                                INSERT INTO platform_model_config(
                                    capability, model_role, provider, model, base_url, max_concurrency,
                                    health_status, enabled, version, updated_by
                                ) VALUES (
                                    :capability, :modelRole, :provider, :model, :baseUrl, :maxConcurrency,
                                    :healthStatus, true, :version, :adminId
                                )
                                RETURNING id::text
                                """)
                                .bind("capability", capability)
                                .bind("modelRole", modelRole)
                                .bind("provider", next.provider())
                                .bind("model", next.model())
                                .bind("baseUrl", next.baseUrl())
                                .bind("maxConcurrency", Parameter.fromOrEmpty(next.maxConcurrency(), Integer.class))
                                .bind("healthStatus", next.healthStatus())
                                .bind("version", current.version() + 1)
                                .bind("adminId", Parameter.fromOrEmpty(adminId, String.class))
                                .map((r, m) -> r.get("id", String.class))
                                .one()
                                .map(UUID::fromString)
                                .flatMap(id -> insertHistory(id, next, current.version() + 1, "update", adminId)
                                        .thenReturn(id)))
                        .flatMap(id -> findCurrent(capability, modelRole)));
    }

    /** 禁用某 (capability, model_role) 当前配置 + history(disable)。无则空（调用方转 404）。 */
    public Mono<Boolean> disable(String capability, String modelRole, String adminId) {
        return findCurrent(capability, modelRole)
                .flatMap(current -> disable(current.id(), adminId)
                        .then(insertHistory(current.id(), current, current.version(), "disable", adminId))
                        .thenReturn(true));
    }

    private Mono<Boolean> disable(UUID id, String adminId) {
        return db.sql("""
                UPDATE platform_model_config
                SET enabled = false, updated_at = now(), updated_by = :adminId
                WHERE id = CAST(:id AS uuid) AND enabled = true
                RETURNING id::text
                """)
                .bind("id", id.toString())
                .bind("adminId", Parameter.fromOrEmpty(adminId, String.class))
                .map((r, m) -> r.get("id", String.class))
                .one()
                .hasElement();
    }

    private Mono<Void> insertHistory(UUID configId, PlatformModelConfig c, int version, String changeType, String changedBy) {
        // configId 仅用于关联；history 表不设 FK（append-only 审计）
        return db.sql("""
                INSERT INTO platform_model_config_history(
                    capability, model_role, provider, model, base_url, max_concurrency,
                    health_status, version, changed_by, change_type
                ) VALUES (
                    :capability, :modelRole, :provider, :model, :baseUrl, :maxConcurrency,
                    :healthStatus, :version, :changedBy, :changeType
                )
                """)
                .bind("capability", c.capability())
                .bind("modelRole", c.modelRole())
                .bind("provider", c.provider())
                .bind("model", c.model())
                .bind("baseUrl", c.baseUrl())
                .bind("maxConcurrency", Parameter.fromOrEmpty(c.maxConcurrency(), Integer.class))
                .bind("healthStatus", c.healthStatus())
                .bind("version", version)
                .bind("changedBy", Parameter.fromOrEmpty(changedBy, String.class))
                .bind("changeType", changeType)
                .then();
    }

    private static PlatformModelConfig map(Row row, RowMetadata meta) {
        return new PlatformModelConfig(
                uuidFromString(row.get("id", String.class)),
                row.get("capability", String.class),
                row.get("model_role", String.class),
                row.get("provider", String.class),
                row.get("model", String.class),
                row.get("base_url", String.class),
                row.get("max_concurrency", Integer.class),
                row.get("health_status", String.class),
                row.get("enabled", Boolean.class),
                row.get("version", Integer.class),
                row.get("updated_by", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class)));
    }

    private static UUID uuidFromString(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
