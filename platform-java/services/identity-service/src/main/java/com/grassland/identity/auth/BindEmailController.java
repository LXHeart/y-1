package com.grassland.identity.auth;
import com.grassland.identity.notify.SmtpMailSender;
import com.grassland.identity.organization.CurrentAccountResolver;
import com.grassland.identity.security.EmailVerificationService;
import com.grassland.identity.security.LoginRateLimiter;
import com.grassland.identity.user.AuthUser;
import com.grassland.identity.user.UserLookup;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 子账号绑定邮箱（任务书 #49 D10）。建号时不填邮箱（email 列为占位符），成员登录后在此
 * 自助绑定：两步 = 发验证码到目标邮箱（登录态 + 双层频控）→ 验码换绑（UNIQUE 冲突 409）。
 *
 * <p>绑定成功后：账号名与邮箱均可登录（双查不变）、站内信知会（EmailBound → SYSTEM 类）、
 * 邮件外发不再被占位短路。只支持换绑到新邮箱，不支持解绑回占位符。
 */
@RestController
public class BindEmailController {

    private final CurrentAccountResolver accounts;
    private final EmailVerificationService codes;
    private final LoginRateLimiter rateLimiter;
    private final SmtpMailSender mailSender;
    private final UserLookup users;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;
    private final org.springframework.r2dbc.core.DatabaseClient db;

    public BindEmailController(CurrentAccountResolver accounts, EmailVerificationService codes,
            LoginRateLimiter rateLimiter, SmtpMailSender mailSender, UserLookup users,
            OutboxRepository outbox, TransactionalOperator transactions,
            org.springframework.r2dbc.core.DatabaseClient db) {
        this.accounts = accounts;
        this.codes = codes;
        this.rateLimiter = rateLimiter;
        this.mailSender = mailSender;
        this.users = users;
        this.outbox = outbox;
        this.transactions = transactions;
        this.db = db;
    }

    /** 第一步：发验证码到目标邮箱（登录态；目标邮箱 pending 频控 + 账号级限流双闸）。 */
    @PostMapping(value = "/api/me/bind-email/code", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> sendCode(@RequestBody Map<String, String> body,
            ServerHttpRequest request) {
        String email = normalizedEmail(body.get("email"));
        return accounts.resolve(request).flatMap(account -> {
            LoginRateLimiter.CheckResult rate = rateLimiter.check(
                    com.grassland.identity.identityprofile.DeviceFingerprint.from(request).ipAddress(),
                    "bind-email:" + account.id());
            if (!rate.allowed()) {
                return Mono.just(error(429, "验证码请求过于频繁，请稍后再试"));
            }
            if (!mailSender.isConfigured()) {
                return Mono.just(error(500, "邮件服务未配置，暂无法绑定邮箱"));
            }
            return codes.createCode(email)
                    .flatMap(code -> {
                        mailSender.sendVerificationCode(email, code);
                        return Mono.just(ok());
                    })
                    .onErrorResume(e -> Mono.just(error(400, e.getMessage())));
        });
    }

    /** 第二步：验码并换绑（同事务：UPDATE email + outbox 事件；UNIQUE 冲突 → 409）。 */
    @PostMapping(value = "/api/me/bind-email", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> bind(@RequestBody Map<String, String> body,
            ServerHttpRequest request) {
        String email = normalizedEmail(body.get("email"));
        String code = body.get("code");
        if (code == null || code.isBlank()) {
            return Mono.just(error(400, "验证码必填"));
        }
        return accounts.resolve(request).flatMap(account -> codes.verifyAndConsume(email, code.trim())
                .flatMap(verified -> {
                    if (!verified) {
                        return Mono.just(error(400, "验证码错误或已过期"));
                    }
                    return users.findByEmail(email).hasElement()
                            .flatMap(taken -> taken
                                    ? Mono.just(error(409, "该邮箱已被其他账号使用"))
                                    : doBind(account, email));
                }));
    }

    private Mono<ResponseEntity<Map<String, Object>>> doBind(AuthUser account, String email) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", account.id());
        payload.put("email", email);
        EventEnvelope event = new EventEnvelope(UUID.randomUUID().toString(), "EmailBound",
                "AppUser", account.id(), 1, Instant.now(), null, payload);
        return transactions.transactional(outbox.append(event)
                .then(db.sql("UPDATE app_users SET email = :email, updated_at = NOW()"
                        + " WHERE id = CAST(:id AS uuid)")
                        .bind("email", email).bind("id", account.id())
                        .fetch().rowsUpdated())
                .then())
                .thenReturn(ok())
                .onErrorResume(DataIntegrityViolationException.class,
                        e -> Mono.just(error(409, "该邮箱已被其他账号使用")));
    }

    private static String normalizedEmail(String email) {
        if (email == null || email.isBlank() || email.length() > 254 || !email.contains("@")) {
            throw new IdentityException(400, "邮箱格式不正确");
        }
        return email.trim().toLowerCase();
    }

    private ResponseEntity<Map<String, Object>> ok() {
        return ResponseEntity.ok(Map.of("success", true, "data", Map.of("bound", true)));
    }

    private ResponseEntity<Map<String, Object>> error(int status, String msg) {
        return ResponseEntity.status(status).body(Map.of("success", false, "error", msg));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }
}
