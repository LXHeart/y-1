package com.grassland.intelligence.ai.controlplane;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import static com.grassland.intelligence.config.R2dbcBindings.nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 受信平台端点仓储（任务书 #58 S1.2，照 {@link PlatformModelConfigRepository} 裸 SQL 惯例）。
 *
 * <p>无 history 表、无软删——这是 SSRF 白名单而非审计对象：增删的可见影响就是下一次平台
 * provider 校验的放行/拒绝，写后失效事件（{@code TrustedOriginsChangedEvent}）即审计面。
 */
@Component
public class PlatformTrustedOriginRepository {

    private static final String SELECT_COLS =
            "id::text, origin, label, enabled, version, updated_by, updated_at, created_at";

    private final DatabaseClient db;

    public PlatformTrustedOriginRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 全部行（治理台列表，含已停用）。 */
    public Flux<PlatformTrustedOrigin> listAll() {
        return db.sql("SELECT " + SELECT_COLS + " FROM platform_trusted_origin"
                + " ORDER BY created_at, id")
                .map(PlatformTrustedOriginRepository::map)
                .all();
    }

    /** 启用中的 origin 集（策略缓存数据源）。 */
    public Flux<String> listEnabledOrigins() {
        return db.sql("SELECT origin FROM platform_trusted_origin WHERE enabled = true")
                .map((r, m) -> r.get("origin", String.class))
                .all();
    }

    public Mono<PlatformTrustedOrigin> findById(UUID id) {
        return db.sql("SELECT " + SELECT_COLS + " FROM platform_trusted_origin"
                + " WHERE id = CAST(:id AS uuid)")
                .bind("id", id.toString())
                .map(PlatformTrustedOriginRepository::map)
                .one();
    }

    public Mono<PlatformTrustedOrigin> create(String origin, String label, String adminId) {
        return db.sql("""
                INSERT INTO platform_trusted_origin(origin, label, updated_by)
                VALUES (:origin, :label, CAST(:updatedBy AS uuid))
                RETURNING """ + " " + SELECT_COLS)
                .bind("origin", origin)
                .bind("label", label == null ? "" : label)
                .bind("updatedBy", nullable(adminId, String.class))
                .map(PlatformTrustedOriginRepository::map)
                .one();
    }

    /**
     * 乐观锁更新：{@code WHERE version = :expectedVersion} 不命中即空结果
     * （调用方区分 404「行不存在」与 409「版本冲突」）。
     */
    public Mono<PlatformTrustedOrigin> update(
            UUID id, String origin, String label, boolean enabled, int expectedVersion, String adminId) {
        return db.sql("""
                UPDATE platform_trusted_origin
                SET origin = :origin, label = :label, enabled = :enabled,
                    version = version + 1, updated_by = CAST(:updatedBy AS uuid), updated_at = now()
                WHERE id = CAST(:id AS uuid) AND version = :expectedVersion
                RETURNING """ + " " + SELECT_COLS)
                .bind("id", id.toString())
                .bind("origin", origin)
                .bind("label", label == null ? "" : label)
                .bind("enabled", enabled)
                .bind("expectedVersion", expectedVersion)
                .bind("updatedBy", nullable(adminId, String.class))
                .map(PlatformTrustedOriginRepository::map)
                .one();
    }

    public Mono<Boolean> delete(UUID id) {
        return db.sql("DELETE FROM platform_trusted_origin WHERE id = CAST(:id AS uuid) RETURNING id::text")
                .bind("id", id.toString())
                .map((r, m) -> r.get("id", String.class))
                .one()
                .hasElement();
    }

    private static PlatformTrustedOrigin map(Row row, RowMetadata meta) {
        String updatedBy = row.get("updated_by", String.class);
        return new PlatformTrustedOrigin(
                UUID.fromString(row.get("id", String.class)),
                row.get("origin", String.class),
                row.get("label", String.class),
                Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
                row.get("version", Integer.class) == null ? 0 : row.get("version", Integer.class),
                updatedBy == null ? null : UUID.fromString(updatedBy),
                toInstant(row.get("updated_at", OffsetDateTime.class)),
                toInstant(row.get("created_at", OffsetDateTime.class)));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
