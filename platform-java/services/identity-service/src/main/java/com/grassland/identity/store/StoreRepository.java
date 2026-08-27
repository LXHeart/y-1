package com.grassland.identity.store;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Store 数据访问（R2DBC {@link DatabaseClient} 手写 SQL，与 organization/ 风格一致）。
 *
 * <p>列名 snake_case（DB）↔ camelCase（record）在 {@link #map(Readable)} 转换；timestamptz 经 OffsetDateTime 转 Instant。
 */
@Component
public class StoreRepository {

    private static final String SELECT_COLS =
            "id::text, organization_id::text, name, status, created_at, updated_at";

    private final DatabaseClient db;

    public StoreRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Store> create(String organizationId, String name) {
        String id = UUID.randomUUID().toString();
        return db.sql("""
                INSERT INTO store(id, organization_id, name, status)
                VALUES (CAST(:id AS uuid), CAST(:org AS uuid), :name, 'active')
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("org", organizationId).bind("name", name)
                .map(StoreRepository::map).one();
    }

    // 三个查询统一带 deleted_at IS NULL：删除（软删，V47）后门店从列表、授权校验
    // （StoreAuthorization.ensureStoreInOrg）、子账号挂店校验全部消失——收口在此。

    public Mono<Store> findById(String id) {
        return db.sql("SELECT " + SELECT_COLS
                        + " FROM store WHERE id = CAST(:id AS uuid) AND deleted_at IS NULL")
                .bind("id", id)
                .map(StoreRepository::map).one();
    }

    public Flux<Store> findByOrganization(String organizationId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM store WHERE organization_id = CAST(:org AS uuid)"
                        + " AND deleted_at IS NULL ORDER BY created_at")
                .bind("org", organizationId)
                .map(StoreRepository::map).all();
    }

    public Mono<Store> findByOrganizationAndId(String organizationId, String id) {
        return db.sql("SELECT " + SELECT_COLS
                        + " FROM store WHERE organization_id = CAST(:org AS uuid) AND id = CAST(:id AS uuid)"
                        + " AND deleted_at IS NULL")
                .bind("org", organizationId).bind("id", id)
                .map(StoreRepository::map).one();
    }

    /** 停用/恢复（可逆）：单边胜出的 guarded UPDATE，0 行=状态不符或已删，回查给可读错误。 */
    public Mono<Long> updateStatusGuarded(String storeId, String to, String from) {
        return db.sql("UPDATE store SET status = :to, updated_at = NOW()"
                        + " WHERE id = CAST(:id AS uuid) AND status = :from AND deleted_at IS NULL")
                .bind("to", to).bind("id", storeId).bind("from", from)
                .fetch().rowsUpdated();
    }

    /** 软删（V47）：置 deleted_at；守卫（成员/任务/最后一家店）由调用方先行。 */
    public Mono<Long> markDeleted(String storeId) {
        return db.sql("UPDATE store SET deleted_at = NOW(), updated_at = NOW()"
                        + " WHERE id = CAST(:id AS uuid) AND deleted_at IS NULL")
                .bind("id", storeId)
                .fetch().rowsUpdated();
    }

    /** 组织内未删门店数（守卫①：主体必须保留至少一家门店）。 */
    public Mono<Long> countActiveByOrganization(String organizationId) {
        return db.sql("SELECT COUNT(*)::int AS c FROM store"
                        + " WHERE organization_id = CAST(:org AS uuid) AND deleted_at IS NULL")
                .bind("org", organizationId)
                .map(row -> row.get("c", Integer.class).longValue()).one();
    }

    private static Store map(Readable row) {
        return new Store(
                row.get("id", String.class),
                row.get("organization_id", String.class),
                row.get("name", String.class),
                row.get("status", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class))
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
