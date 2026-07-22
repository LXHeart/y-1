package com.grassland.identity.organization;

import io.r2dbc.spi.Readable;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Organization 数据访问（R2DBC {@link DatabaseClient} 手写 SQL，与 user/ 包风格一致）。
 *
 * <p>列名 snake_case（DB）↔ camelCase（record）在 {@link #map(Row)} 转换；timestamptz 经 OffsetDateTime 转 Instant。
 */
@Component
public class OrganizationRepository {

    private static final String SELECT_COLS =
            "id::text, owner_account_id::text, name, status, permission_tier, created_at, updated_at";

    private final DatabaseClient db;

    public OrganizationRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Organization> create(String ownerAccountId, String name) {
        String id = UUID.randomUUID().toString();
        return db.sql("""
                INSERT INTO organization(id, owner_account_id, name, status)
                VALUES (CAST(:id AS uuid), CAST(:owner AS uuid), :name, 'active')
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("owner", ownerAccountId).bind("name", name)
                .map(OrganizationRepository::map).one();
    }

    public Mono<Organization> findById(String id) {
        return db.sql("SELECT " + SELECT_COLS + " FROM organization WHERE id = CAST(:id AS uuid)")
                .bind("id", id)
                .map(OrganizationRepository::map).one();
    }

    public Flux<Organization> findByOwner(String ownerAccountId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM organization WHERE owner_account_id = CAST(:owner AS uuid) ORDER BY created_at")
                .bind("owner", ownerAccountId)
                .map(OrganizationRepository::map).all();
    }

    /** 升级商家准入权限（Slice 2F）。返回受影响行数；幂等语义由调用方按单调升级校验保证。 */
    public Mono<Long> updatePermissionTier(String id, String tier) {
        return db.sql("UPDATE organization SET permission_tier = :tier, updated_at = now() WHERE id = CAST(:id AS uuid)")
                .bind("tier", tier).bind("id", id)
                .fetch().rowsUpdated();
    }

    private static Organization map(Readable row) {
        return new Organization(
                row.get("id", String.class),
                row.get("owner_account_id", String.class),
                row.get("name", String.class),
                row.get("status", String.class),
                row.get("permission_tier", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class))
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
