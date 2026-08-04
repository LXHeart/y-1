package com.grassland.identity.auth;

import com.grassland.identity.security.Argon2PasswordHasher;
import com.grassland.identity.security.EmailVerificationService;
import com.grassland.identity.security.PasswordVerifier;
import com.grassland.identity.session.SessionWriter;
import com.grassland.identity.user.AuthUser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.grassland.identity.event.OutboxRepository;
import com.grassland.identity.event.EventEnvelope;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
public class RegisterController {
    private final EmailVerificationService codeService;
    private final DatabaseClient db;
    private final SessionWriter sessionWriter;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;
    private final Argon2PasswordHasher argon2Hasher;

    public RegisterController(EmailVerificationService codeService, DatabaseClient db, SessionWriter sessionWriter,
                              OutboxRepository outbox, TransactionalOperator transactions,
                              Argon2PasswordHasher argon2Hasher) {
        this.codeService = codeService; this.db = db; this.sessionWriter = sessionWriter; this.outbox = outbox;
        this.transactions = transactions;
        this.argon2Hasher = argon2Hasher;
    }

    @PostMapping(value = "/api/auth/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> register(@RequestBody Map<String, String> body,
                                                              ServerHttpRequest request) {
        String email = body.get("email");
        String password = body.get("password");
        String displayName = body.get("displayName");
        String code = body.get("verificationCode");
        if (email == null || password == null || code == null || displayName == null
            || email.isBlank() || password.length() < 8 || code.trim().length() != 6) {
            return Mono.just(error(400, "\u8bf7\u586b\u5199\u5b8c\u6574\u7684\u6ce8\u518c\u4fe1\u606f"));
        }
        String normalizedEmail = email.trim().toLowerCase();
        return codeService.verifyAndConsume(normalizedEmail, code.trim())
            .flatMap(valid -> {
                if (!valid) return Mono.just(error(400, "\u9a8c\u8bc1\u7801\u65e0\u6548\u6216\u5df2\u8fc7\u671f"));
                String userId = UUID.randomUUID().toString();
                // GL-P3-IDENTITY-001：新注册直接落 argon2id（不再 bcrypt-12）。
                // Argon2 是 64MB/3 轮的 CPU+内存重操作，必须在 boundedElastic 上跑，不能占 Netty 事件循环。
                return Mono.fromCallable(() -> argon2Hasher.hash(password))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(hash -> transactions.transactional(
                    db.sql("INSERT INTO app_users(id, email, password_hash, display_name, role, status) "
                    + "VALUES (CAST(:id AS uuid), :email, :hash, :name, 'user', 'active') ON CONFLICT (email) DO NOTHING RETURNING id")
                    .bind("id", userId).bind("email", normalizedEmail).bind("hash", hash).bind("name", displayName.trim())
                    .map(r -> r.get("id", String.class)).one()
                    .switchIfEmpty(Mono.just(error(409, "\u8be5\u90ae\u7bb1\u5df2\u5b58\u5728")).flatMap(e -> Mono.empty()))
                    .flatMap(uid -> awardCredits(uid).then(outbox.append(new EventEnvelope(
                        UUID.randomUUID().toString(), "UserRegistered", "User", uid, 1, java.time.Instant.now(), null, Map.of("email", normalizedEmail, "userId", uid)))).then(sessionWriter.createSession(
                        new AuthUser(uid, normalizedEmail, displayName.trim(), "user", "active"), request))
                        .map(session -> ResponseEntity.status(201)
                            .header("Set-Cookie", session.setCookieHeader())
                            .body(Map.of("success", true, "data", Map.of("user", buildUser(uid, normalizedEmail, displayName.trim())))))))
                    .switchIfEmpty(Mono.just(error(409, "\u8be5\u90ae\u7bb1\u5df2\u5b58\u5728"))));
            });
    }

    private Mono<Void> awardCredits(String userId) {
        return db.sql("INSERT INTO user_credits(user_id, balance, total_earned, total_spent) "
            + "VALUES (CAST(:id AS uuid), 3, 3, 0) ON CONFLICT DO NOTHING")
            .bind("id", userId).then();
    }

    private Map<String, Object> buildUser(String id, String email, String displayName) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("id", id); u.put("email", email); u.put("displayName", displayName); u.put("role", "user");
        return u;
    }

    private ResponseEntity<Map<String, Object>> error(int status, String msg) {
        return ResponseEntity.status(status).body(Map.of("success", false, "error", msg));
    }
}
