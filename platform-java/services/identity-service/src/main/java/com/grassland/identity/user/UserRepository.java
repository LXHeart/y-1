package com.grassland.identity.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class UserRepository {
    private static final Logger log = LoggerFactory.getLogger(UserRepository.class);

    private final DatabaseClient db;

    public UserRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Void> recordLogin(String userId) {
        return db.sql("UPDATE app_users SET last_login_at = now(), updated_at = now() WHERE id = CAST(:id AS uuid)")
            .bind("id", userId)
            .then();
    }

    /**
     * 升级密码哈希为 Argon2id（GL-P3-IDENTITY-001）。
     * 异步执行，失败不抛异常（不影响登录流程）。
     */
    public Mono<Void> upgradePasswordHash(String userId, String newHash) {
        return db.sql("UPDATE app_users SET password_hash = :hash, updated_at = now() WHERE id = CAST(:id AS uuid)")
            .bind("hash", newHash)
            .bind("id", userId)
            .then()
            .onErrorResume(e -> {
                // 记录日志但不抛异常，避免影响登录成功主流程
                log.warn("Failed to upgrade password hash for user {}: {}", userId, e.getMessage());
                return Mono.empty();
            });
    }
}
