package com.grassland.identity.identityprofile;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * account_active_identity 数据访问（账号级活动身份，一行/账号）。
 * setActive 用 {@code INSERT ... ON CONFLICT(account_id) DO UPDATE} upsert；clear 置 NULL（切回消费者）。
 */
@Component
public class ActiveIdentityRepository {

    private static final String SELECT_COLS =
            "account_id::text, active_identity_type, updated_at";

    private final DatabaseClient db;

    public ActiveIdentityRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 返回账号活动身份；无记录（默认消费者）返回空 Mono。 */
    public Mono<ActiveIdentity> findByAccount(String accountId) {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM account_active_identity WHERE account_id = CAST(:acct AS uuid)")
                .bind("acct", accountId)
                .map(ActiveIdentityRepository::map).one();
    }

    /** 设置活动身份（upsert：已有则覆盖 → 强制「同一时间仅一个」）。 */
    public Mono<ActiveIdentity> setActive(String accountId, String identityType) {
        return db.sql("""
                INSERT INTO account_active_identity(account_id, active_identity_type)
                VALUES (CAST(:acct AS uuid), :type)
                ON CONFLICT (account_id) DO UPDATE
                  SET active_identity_type = :type, updated_at = now()
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("acct", accountId).bind("type", identityType)
                .map(ActiveIdentityRepository::map).one();
    }

    /** 切回消费者（active 置 NULL）；返回受影响行数（0 表示本就是消费者，幂等）。 */
    public Mono<Long> clear(String accountId) {
        return db.sql("UPDATE account_active_identity"
                + " SET active_identity_type = NULL, updated_at = now()"
                + " WHERE account_id = CAST(:acct AS uuid)")
                .bind("acct", accountId)
                .fetch().rowsUpdated();
    }

    private static ActiveIdentity map(Readable row) {
        return new ActiveIdentity(
                row.get("account_id", String.class),
                row.get("active_identity_type", String.class),
                toInstant(row.get("updated_at", OffsetDateTime.class))
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
