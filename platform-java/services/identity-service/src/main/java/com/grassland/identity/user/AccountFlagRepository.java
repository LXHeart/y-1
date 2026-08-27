package com.grassland.identity.user;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * account_flag 数据访问（任务书 #48 V42）。
 *
 * <p>{@code must_change_password} 是账号级标记：主体代建/重置密码的子账号置位，本人改密成功清除。
 * 放旁表而非 {@code app_users} 列的原因见表定义注释（V42）：共享表归 bootstrap 管、IT 里晚于 Flyway 手建。
 *
 * <p>查询口径一律 {@link #mustChangePassword}：无行 = false（存量账号零感知）。
 */
@Component
public class AccountFlagRepository {

    private final DatabaseClient db;

    public AccountFlagRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 无行视为 false；行内显式 false 同样返回 false。查不到表等基础设施异常不吞。 */
    public Mono<Boolean> mustChangePassword(String accountId) {
        return db.sql("SELECT must_change_password FROM account_flag WHERE account_id = CAST(:id AS uuid)")
                .bind("id", accountId)
                .map(row -> Boolean.TRUE.equals(row.get("must_change_password", Boolean.class)))
                .one();
    }

    /** 首登强制改密置位；幂等。管理员直建与重置密码两个入口共用。 */
    public Mono<Void> markMustChangePassword(String accountId) {
        return db.sql("""
                        INSERT INTO account_flag(account_id, must_change_password)
                        VALUES (CAST(:id AS uuid), TRUE)
                        ON CONFLICT (account_id) DO UPDATE
                            SET must_change_password = TRUE, updated_at = NOW()
                        """)
                .bind("id", accountId)
                .then();
    }

    /** 本人改密成功后清除；幂等。 */
    public Mono<Void> clearMustChangePassword(String accountId) {
        return db.sql("""
                        INSERT INTO account_flag(account_id, must_change_password)
                        VALUES (CAST(:id AS uuid), FALSE)
                        ON CONFLICT (account_id) DO UPDATE
                            SET must_change_password = FALSE, updated_at = NOW()
                        """)
                .bind("id", accountId)
                .then();
    }
}
