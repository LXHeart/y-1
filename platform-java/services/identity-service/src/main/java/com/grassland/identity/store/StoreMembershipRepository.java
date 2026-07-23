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
 * store_membership 数据访问（R2DBC {@link DatabaseClient} 手写 SQL，与 membership/ 风格一致）。
 * 一个账号在同一 store 至多一条（UNIQUE(store_id, account_id)）。
 */
@Component
public class StoreMembershipRepository {

    private static final String SELECT_COLS =
            "id::text, store_id::text, account_id::text, role, created_at, updated_at";

    private final DatabaseClient db;

    public StoreMembershipRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<StoreMembership> create(String storeId, String accountId, String role) {
        String id = UUID.randomUUID().toString();
        return db.sql("""
                INSERT INTO store_membership(id, store_id, account_id, role)
                VALUES (CAST(:id AS uuid), CAST(:store AS uuid), CAST(:acct AS uuid), :role)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("store", storeId).bind("acct", accountId).bind("role", role)
                .map(StoreMembershipRepository::map).one();
    }

    public Flux<StoreMembership> findByStore(String storeId) {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM store_membership WHERE store_id = CAST(:store AS uuid) ORDER BY created_at")
                .bind("store", storeId)
                .map(StoreMembershipRepository::map).all();
    }

    public Mono<String> findRole(String storeId, String accountId) {
        return db.sql("SELECT role FROM store_membership"
                + " WHERE store_id = CAST(:store AS uuid) AND account_id = CAST(:acct AS uuid)")
                .bind("store", storeId).bind("acct", accountId)
                .map(row -> row.get("role", String.class)).one();
    }

    public Mono<Long> deleteByStoreAndAccount(String storeId, String accountId) {
        return db.sql("DELETE FROM store_membership"
                + " WHERE store_id = CAST(:store AS uuid) AND account_id = CAST(:acct AS uuid)")
                .bind("store", storeId).bind("acct", accountId)
                .fetch().rowsUpdated();
    }

    public Mono<Long> countByStoreAndRole(String storeId, String role) {
        return db.sql("SELECT COUNT(*)::bigint AS c FROM store_membership"
                + " WHERE store_id = CAST(:store AS uuid) AND role = :role")
                .bind("store", storeId).bind("role", role)
                .map(row -> row.get("c", Long.class)).one();
    }

    private static StoreMembership map(Readable row) {
        return new StoreMembership(
                row.get("id", String.class),
                row.get("store_id", String.class),
                row.get("account_id", String.class),
                row.get("role", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class))
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
