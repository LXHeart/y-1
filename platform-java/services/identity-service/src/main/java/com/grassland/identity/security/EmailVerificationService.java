package com.grassland.identity.security;

import java.security.SecureRandom;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class EmailVerificationService {
    private static final int CODE_LENGTH = 6;
    private static final long CODE_TTL_MINUTES = 5;
    private static final int MAX_PENDING = 5;
    private final SecureRandom random = new SecureRandom();
    private final DatabaseClient db;

    public EmailVerificationService(DatabaseClient db) { this.db = db; }

    public Mono<String> createCode(String email) {
        String normalized = email == null ? null : email.trim().toLowerCase();
        return db.sql("SELECT count(*)::int AS n FROM email_verification_codes WHERE email = :email AND used = false AND expires_at > now()")
            .bind("email", normalized).map(r -> r.get("n", Integer.class)).one()
            .flatMap(count -> {
                if (count != null && count >= MAX_PENDING) {
                    return Mono.error(new IllegalStateException("验证码发送过于频繁，请稍后再试"));
                }
                String code = generateCode();
                return db.sql("INSERT INTO email_verification_codes(email, code, expires_at) VALUES (:email, :code, now() + interval '"
                    + CODE_TTL_MINUTES + " minutes')")
                    .bind("email", normalized).bind("code", code).then().thenReturn(code);
            });
    }

    public Mono<Boolean> verifyAndConsume(String email, String code) {
        String normalized = email == null ? null : email.trim().toLowerCase();
        return db.sql("SELECT id FROM email_verification_codes WHERE email = :email AND code = :code AND used = false AND expires_at > now() ORDER BY created_at DESC LIMIT 1")
            .bind("email", normalized).bind("code", code).map(r -> r.get("id", String.class)).one()
            .flatMap(id -> db.sql("UPDATE email_verification_codes SET used = true WHERE id = CAST(:id AS uuid)")
                .bind("id", id).then().thenReturn(true))
            .defaultIfEmpty(false);
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) sb.append(random.nextInt(10));
        return sb.toString();
    }
}
