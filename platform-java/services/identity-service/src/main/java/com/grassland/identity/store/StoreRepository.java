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

    public Mono<Store> findById(String id) {
        return db.sql("SELECT " + SELECT_COLS + " FROM store WHERE id = CAST(:id AS uuid)")
                .bind("id", id)
                .map(StoreRepository::map).one();
    }

    public Flux<Store> findByOrganization(String organizationId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM store WHERE organization_id = CAST(:org AS uuid) ORDER BY created_at")
                .bind("org", organizationId)
                .map(StoreRepository::map).all();
    }

    public Mono<Store> findByOrganizationAndId(String organizationId, String id) {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM store WHERE organization_id = CAST(:org AS uuid) AND id = CAST(:id AS uuid)")
                .bind("org", organizationId).bind("id", id)
                .map(StoreRepository::map).one();
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
