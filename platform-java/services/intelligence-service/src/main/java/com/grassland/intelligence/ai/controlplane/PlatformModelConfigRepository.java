package com.grassland.intelligence.ai.controlplane;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import static com.grassland.intelligence.config.R2dbcBindings.nullable;
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
    private final PlatformProviderCredentialRepository credentials;

    public PlatformModelConfigRepository(
            DatabaseClient db, PlatformProviderCredentialRepository credentials) {
        this.db = db;
        this.credentials = credentials;
    }

    /**
     * 解析（或按需创建）该目的地的凭据，返回其 id（任务书 #47 S1）。
     *
     * <p>写入侧必须挂上 {@code credential_id}，否则 S1 与 S2 之间新建的模型配置行会是 NULL，
     * V47 收 NOT NULL 时失败。自动建出的凭据无密钥——执行侧回落 env 兜底，语义与 V46 回填一致（D1/D8）。
     * 命名沿用 V46 的确定性规则 {@code provider-host}；标签撞车时补随机后缀兜底（标签唯一索引限有效行）。
     */
    private Mono<UUID> resolveCredentialId(String provider, String baseUrl, String adminId) {
        return credentials.findEnabledByDestination(provider, baseUrl)
                .map(PlatformProviderCredential::id)
                .switchIfEmpty(Mono.defer(() -> credentials
                        .create(defaultCredentialName(provider, baseUrl), provider, baseUrl,
                                null, null, null, adminId)
                        .onErrorResume(DataIntegrityViolationException.class,
                                error -> credentials.create(
                                        defaultCredentialName(provider, baseUrl) + "-"
                                                + UUID.randomUUID().toString().substring(0, 8),
                                        provider, baseUrl, null, null, null, adminId))));
    }

    /** {@code provider-host}，与 V46 回填的命名规则一致；无法解析 host 时退化为 {@code provider-default}。 */
    private static String defaultCredentialName(String provider, String baseUrl) {
        String host = "default";
        try {
            String parsed = URI.create(baseUrl).getHost();
            if (parsed != null && !parsed.isBlank()) {
                host = parsed;
            }
        } catch (IllegalArgumentException ignored) {
            // baseUrl 已过 PlatformProviderPolicy 校验；此处只是命名兜底，不重复报错
        }
        return provider + "-" + host;
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

    /** 按不可变配置 ID 读取历史版本；供创作上下文快照复现运行时配置。 */
    public Mono<PlatformModelConfig> findById(UUID id) {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM platform_model_config WHERE id = CAST(:id AS uuid)")
                .bind("id", id.toString())
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
        return resolveCredentialId(c.provider(), c.baseUrl(), adminId).flatMap(credentialId -> db.sql("""
                INSERT INTO platform_model_config(
                    capability, model_role, provider, model, base_url, max_concurrency,
                    health_status, enabled, version, updated_by, credential_id
                ) VALUES (
                    :capability, :modelRole, :provider, :model, :baseUrl, :maxConcurrency,
                    :healthStatus, true, 1, :adminId, CAST(:credentialId AS uuid)
                )
                RETURNING id::text
                """)
                .bind("capability", c.capability())
                .bind("modelRole", c.modelRole())
                .bind("provider", c.provider())
                .bind("model", c.model())
                .bind("baseUrl", c.baseUrl())
                .bind("maxConcurrency", nullable(c.maxConcurrency(), Integer.class))
                .bind("healthStatus", c.healthStatus())
                .bind("adminId", nullable(adminId, String.class))
                .bind("credentialId", credentialId.toString())
                .map((r, m) -> r.get("id", String.class))
                .one()
                .map(UUID::fromString)
                .flatMap(id -> createConcurrencySlots(id, c.maxConcurrency())
                        .then(insertHistory(id, c, 1, "create", adminId))
                        .thenReturn(id)));
    }

    /**
     * 修订：disable 旧 enabled 行 + 插入新版本（version=旧+1, enabled=true）+ history(update)。
     * 必须在事务内调用。旧行不存在时返回空（调用方转 404）。
     */
    public Mono<PlatformModelConfig> revise(String capability, String modelRole, PlatformModelConfig next, String adminId) {
        return findCurrent(capability, modelRole)
                .flatMap(current -> disable(current.id(), adminId)
                        .then(resolveCredentialId(next.provider(), next.baseUrl(), adminId))
                        .flatMap(credentialId -> db.sql("""
                                INSERT INTO platform_model_config(
                                    capability, model_role, provider, model, base_url, max_concurrency,
                                    health_status, enabled, version, updated_by, credential_id
                                ) VALUES (
                                    :capability, :modelRole, :provider, :model, :baseUrl, :maxConcurrency,
                                    :healthStatus, true, :version, :adminId, CAST(:credentialId AS uuid)
                                )
                                RETURNING id::text
                                """)
                                .bind("capability", capability)
                                .bind("modelRole", modelRole)
                                .bind("provider", next.provider())
                                .bind("model", next.model())
                                .bind("baseUrl", next.baseUrl())
                                .bind("maxConcurrency", nullable(next.maxConcurrency(), Integer.class))
                                .bind("healthStatus", next.healthStatus())
                                .bind("version", current.version() + 1)
                                .bind("adminId", nullable(adminId, String.class))
                                .bind("credentialId", credentialId.toString())
                                .map((r, m) -> r.get("id", String.class))
                                .one()
                                .map(UUID::fromString)
                                .flatMap(id -> createConcurrencySlots(id, next.maxConcurrency())
                                        .then(insertHistory(id, next, current.version() + 1, "update", adminId))
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
                .bind("adminId", nullable(adminId, String.class))
                .map((r, m) -> r.get("id", String.class))
                .one()
                .hasElement();
    }

    private Mono<Void> createConcurrencySlots(UUID configId, Integer maximum) {
        if (maximum == null) {
            return Mono.empty();
        }
        return db.sql("""
                INSERT INTO platform_model_concurrency_slot(config_id, slot_no)
                SELECT CAST(:configId AS uuid), generate_series(1, :maximum)
                """)
                .bind("configId", configId.toString())
                .bind("maximum", maximum)
                .then();
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
                .bind("maxConcurrency", nullable(c.maxConcurrency(), Integer.class))
                .bind("healthStatus", c.healthStatus())
                .bind("version", version)
                .bind("changedBy", nullable(changedBy, String.class))
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
