package com.grassland.identity.user;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class LegacyUserLookup {
    private final DatabaseClient db;

    public LegacyUserLookup(DatabaseClient db) {
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
}
