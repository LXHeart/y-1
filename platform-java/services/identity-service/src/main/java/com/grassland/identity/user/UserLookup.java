package com.grassland.identity.user;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class UserLookup {
    private final DatabaseClient db;

    public UserLookup(DatabaseClient db) {
        this.db = db;
    }

    public Mono<AuthUser> findById(String id) {
        return db.sql("SELECT id, email, display_name, role, status FROM app_users WHERE id = CAST(:id AS uuid)")
            .bind("id", id)
            .map((row) -> new AuthUser(
                row.get("id", String.class),
                row.get("email", String.class),
                row.get("display_name", String.class),
                row.get("role", String.class),
                row.get("status", String.class)))
            .one();
    }

    /**
     * 按 id 查（含 password_hash）——重认证（MFA）校验当前登录用户的密码用。
     * 与 {@link #findById} 的区别：那个不带哈希（供一般身份解析，避免哈希外泄到调用链）。
     */
    public Mono<LoginUser> findLoginUserById(String id) {
        return db.sql("SELECT id, email, display_name, role, status, password_hash"
                + " FROM app_users WHERE id = CAST(:id AS uuid)")
            .bind("id", id)
            .map((row) -> new LoginUser(
                row.get("id", String.class),
                row.get("email", String.class),
                row.get("display_name", String.class),
                row.get("role", String.class),
                row.get("status", String.class),
                row.get("password_hash", String.class)))
            .one();
    }

    public Mono<LoginUser> findByEmail(String email) {
        return db.sql("SELECT id, email, display_name, role, status, password_hash FROM app_users WHERE email = :email")
            .bind("email", email == null ? null : email.trim().toLowerCase())
            .map((row) -> new LoginUser(
                row.get("id", String.class),
                row.get("email", String.class),
                row.get("display_name", String.class),
                row.get("role", String.class),
                row.get("status", String.class),
                row.get("password_hash", String.class)))
            .one();
    }

    /**
     * 登录标识双查（任务书 #49 D7）：先按 account_username.username 命中子账号，miss 再按邮箱——
     * 存量邮箱账号路径零变化。标识先归一小写（旁表登录名全小写存储）。
     */
    public Mono<LoginUser> findByIdentifier(String identifier) {
        String normalized = identifier == null ? null : identifier.trim().toLowerCase();
        if (normalized == null || normalized.isEmpty()) {
            return Mono.empty();
        }
        return db.sql("""
                SELECT u.id, u.email, u.display_name, u.role, u.status, u.password_hash
                FROM app_users u
                JOIN account_username n ON n.account_id = u.id
                WHERE n.username = :name
                """)
            .bind("name", normalized)
            .map((row) -> toLoginUser(row))
            .one()
            .switchIfEmpty(Mono.defer(() -> findByEmail(normalized)));
    }

    /** 账号的登录名（子账号）；非子账号（无旁表行）为 empty——展示层回退 email。 */
    public Mono<String> findUsernameById(String accountId) {
        return db.sql("SELECT username FROM account_username WHERE account_id = CAST(:id AS uuid)")
            .bind("id", accountId)
            .map(row -> row.get("username", String.class))
            .one();
    }

    private static LoginUser toLoginUser(io.r2dbc.spi.Readable row) {
        return new LoginUser(
            row.get("id", String.class),
            row.get("email", String.class),
            row.get("display_name", String.class),
            row.get("role", String.class),
            row.get("status", String.class),
            row.get("password_hash", String.class));
    }
}
