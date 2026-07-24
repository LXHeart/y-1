package com.grassland.finance.account;

import io.r2dbc.spi.R2dbcDataIntegrityViolationException;
import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * finance_account 数据访问（R2DBC {@link DatabaseClient} 手写 SQL，风格同 marketplace TaskRepository）。草场 Epic 4 Slice 4D。
 *
 * <p>{@link #create} 用 UNIQUE(organization_id) 保证幂等：并发首开户时第二个 INSERT 触发违例 → empty，调用方回退 {@link #findByOrganization}。
 */
@Component
public class AccountRepository {

    private static final String SELECT_COLS =
            "id::text, organization_id::text, balance_cents, currency, created_at, updated_at";

    private final DatabaseClient db;

    public AccountRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 按 org 查账户（至多一行，UNIQUE 保证）；不存在 → empty。 */
    public Mono<Account> findByOrganization(String organizationId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM finance_account WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", organizationId)
                .map(AccountRepository::map).one();
    }

    /** 开户（余额 0）。UNIQUE(organization_id) 违例 → empty（调用方判并发，回退 findByOrganization）。 */
    public Mono<Account> create(String organizationId) {
        String id = UUID.randomUUID().toString();
        return db.sql("""
                INSERT INTO finance_account(id, organization_id)
                VALUES (CAST(:id AS uuid), CAST(:org AS uuid))
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("org", organizationId)
                .map(AccountRepository::map).one()
                .onErrorResume(R2dbcDataIntegrityViolationException.class, e -> Mono.empty());
    }

    private static Account map(Readable row) {
        return new Account(
                row.get("id", String.class),
                row.get("organization_id", String.class),
                row.get("balance_cents", Long.class),
                row.get("currency", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class))
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
