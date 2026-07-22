package com.grassland.identity.user;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

@Component
public class LegacyUserRepository {
    private final DatabaseClient db;

    public LegacyUserRepository(DatabaseClient db) {
        this.db = db;
    }

    public reactor.core.publisher.Mono<Void> recordLogin(String userId) {
        return db.sql("UPDATE app_users SET last_login_at = now(), updated_at = now() WHERE id = CAST(:id AS uuid)")
            .bind("id", userId)
            .then();
    }
}
