package com.grassland.identity.auth;

import com.grassland.identity.admin.FinanceCreditsAdminClient;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.identity.identityprofile.IdentityProfile;
import com.grassland.identity.identityprofile.IdentityProfileRepository;
import com.grassland.identity.identityprofile.IdentityType;
import com.grassland.identity.security.Argon2PasswordHasher;
import com.grassland.identity.security.EmailVerificationService;
import com.grassland.identity.session.SessionWriter;
import com.grassland.identity.user.AuthUser;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
public class RegisterController {
    private static final Logger LOG = LoggerFactory.getLogger(RegisterController.class);

    private final EmailVerificationService codeService;
    private final DatabaseClient db;
    private final SessionWriter sessionWriter;
    private final IdentityProfileRepository identityProfiles;
    private final FinanceCreditsAdminClient financeCredits;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;
    private final Argon2PasswordHasher argon2Hasher;

    public RegisterController(EmailVerificationService codeService, DatabaseClient db, SessionWriter sessionWriter,
                              IdentityProfileRepository identityProfiles, FinanceCreditsAdminClient financeCredits,
                              OutboxRepository outbox,
                              TransactionalOperator transactions,
                              Argon2PasswordHasher argon2Hasher) {
        this.codeService = codeService;
        this.db = db;
        this.sessionWriter = sessionWriter;
        this.identityProfiles = identityProfiles;
        this.financeCredits = financeCredits;
        this.outbox = outbox;
        this.transactions = transactions;
        this.argon2Hasher = argon2Hasher;
    }

    @PostMapping(value = "/api/auth/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> register(@RequestBody RegisterRequest body,
                                                              ServerHttpRequest request) {
        String email = body.email();
        String password = body.password();
        String displayName = body.displayName();
        String code = body.verificationCode();
        if (email == null || password == null || code == null || displayName == null
            || body.confirmPassword() == null || body.initialIdentity() == null
            || email.isBlank() || password.length() < 8 || code.trim().length() != 6) {
            return Mono.just(error(400, "\u8bf7\u586b\u5199\u5b8c\u6574\u7684\u6ce8\u518c\u4fe1\u606f"));
        }
        if (!password.equals(body.confirmPassword())) {
            return Mono.just(error(400, "\u4e24\u6b21\u8f93\u5165\u7684\u5bc6\u7801\u4e0d\u4e00\u81f4"));
        }
        IdentityType initialIdentity;
        try {
            initialIdentity = IdentityType.fromDb(body.initialIdentity());
        } catch (IllegalArgumentException invalidIdentity) {
            return Mono.just(error(400, "\u521d\u59cb\u8eab\u4efd仅支持商家或推荐官"));
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
                                                + "VALUES (CAST(:id AS uuid), :email, :hash, :name, 'user', 'active') "
                                                + "ON CONFLICT (email) DO NOTHING RETURNING id::text")
                                        .bind("id", userId)
                                        .bind("email", normalizedEmail)
                                        .bind("hash", hash)
                                        .bind("name", displayName.trim())
                                        .map(r -> r.get(0, String.class)).one()
                                        .switchIfEmpty(Mono.error(new IdentityException(
                                                409, "\u8be5\u90ae\u7bb1\u5df2\u5b58\u5728")))
                                        .flatMap(uid -> identityProfiles.create(
                                                        uid, initialIdentity.dbValue(), null)
                                                .flatMap(profile -> completeRegistration(
                                                        uid, normalizedEmail, displayName.trim(), profile, request)))))
                        .flatMap(response -> awardRegistrationCredits(userId).thenReturn(response))
                        .onErrorResume(IdentityException.class,
                                registrationError -> Mono.just(error(
                                        registrationError.status(), registrationError.getMessage())));
            });
    }

    private Mono<ResponseEntity<Map<String, Object>>> completeRegistration(
            String userId,
            String email,
            String displayName,
            IdentityProfile identity,
            ServerHttpRequest request) {
        EventEnvelope registered = new EventEnvelope(
                UUID.randomUUID().toString(), "UserRegistered", "User", userId, 1, Instant.now(), null,
                Map.of("email", email, "userId", userId, "initialIdentity", identity.identityType()));
        EventEnvelope opened = new EventEnvelope(
                UUID.randomUUID().toString(), "IdentityOpened", "IdentityProfile", identity.id(), 1,
                Instant.now(), null,
                Map.of("accountId", userId, "identityType", identity.identityType()));
        return outbox.append(registered)
                .then(outbox.append(opened))
                .then(sessionWriter.createSession(
                        new AuthUser(userId, email, displayName, "user", "active"), request))
                .map(session -> ResponseEntity.status(201)
                        .header("Set-Cookie", session.setCookieHeader())
                        .body(Map.of("success", true,
                                "data", Map.of("user", buildUser(userId, email, displayName)))));
    }

    private Mono<Void> awardRegistrationCredits(String userId) {
        return financeCredits.award(userId, 3, "新用户注册赠送", "registration:" + userId)
                .onErrorResume(error -> {
                    LOG.warn("Registration succeeded but initial credits award failed for account {}", userId);
                    return Mono.empty();
                });
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
