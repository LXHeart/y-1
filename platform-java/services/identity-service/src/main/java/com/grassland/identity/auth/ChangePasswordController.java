package com.grassland.identity.auth;

import com.grassland.identity.organization.CurrentAccountResolver;
import com.grassland.identity.security.Argon2PasswordHasher;
import com.grassland.identity.security.PasswordVerifier;
import com.grassland.identity.user.AccountFlagRepository;
import com.grassland.identity.user.LoginUser;
import com.grassland.identity.user.UserLookup;
import com.grassland.identity.user.UserRepository;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * POST /api/auth/change-password（任务书 #48 D3）。
 *
 * <p>两种形态：<b>带 currentPassword</b> 的常规改密；<b>不带</b>——仅当
 * {@code account_flag.must_change_password=true}（管理员代建/重置后的首登态）放行，免输旧密码。
 * 成功即清除标记。Argon2 与注册/登录升级同款 boundedElastic 约束。
 */
@RestController
public class ChangePasswordController {

    private final CurrentAccountResolver accounts;
    private final UserLookup users;
    private final UserRepository userRepository;
    private final AccountFlagRepository flags;
    private final PasswordVerifier passwordVerifier;
    private final Argon2PasswordHasher argon2Hasher;

    public ChangePasswordController(CurrentAccountResolver accounts, UserLookup users,
            UserRepository userRepository, AccountFlagRepository flags, PasswordVerifier passwordVerifier,
            Argon2PasswordHasher argon2Hasher) {
        this.accounts = accounts;
        this.users = users;
        this.userRepository = userRepository;
        this.flags = flags;
        this.passwordVerifier = passwordVerifier;
        this.argon2Hasher = argon2Hasher;
    }

    @PostMapping(value = "/api/auth/change-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> change(@RequestBody ChangePasswordRequest body,
            ServerHttpRequest request) {
        if (body.newPassword() == null || body.newPassword().length() < 8 || body.newPassword().length() > 128) {
            return Mono.just(error(400, "新密码至少 8 位"));
        }
        return accounts.resolve(request)
                .flatMap(principal -> {
                    String accountId = principal.id();
                    return flags.mustChangePassword(accountId).defaultIfEmpty(Boolean.FALSE)
                            .flatMap(mustChange -> requireCurrentWhenNeeded(accountId, body.currentPassword(),
                                    Boolean.TRUE.equals(mustChange)))
                            .then(Mono.fromCallable(() -> argon2Hasher.hash(body.newPassword()))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .flatMap(hash -> userRepository.updatePasswordHash(accountId, hash)
                                            .then(flags.clearMustChangePassword(accountId))))
                            .thenReturn(ResponseEntity.ok()
                                    .body(Map.<String, Object>of("success", true)));
                });
    }

    /** 标记未置位时必须验旧密码；置位则跳过（子账号拿到的是线下转交的初始密码）。 */
    private Mono<Void> requireCurrentWhenNeeded(String accountId, String currentPassword, boolean mustChange) {
        if (mustChange) {
            return Mono.empty();
        }
        if (currentPassword == null || currentPassword.isBlank()) {
            return Mono.error(new IdentityException(400, "请输入当前密码"));
        }
        return users.findLoginUserById(accountId)
                .flatMap(login -> verifyCurrent(currentPassword, login));
    }

    private Mono<Void> verifyCurrent(String currentPassword, LoginUser login) {
        return passwordVerifier.verify(currentPassword, login.passwordHash())
                ? Mono.empty()
                : Mono.error(new IdentityException(400, "当前密码不正确"));
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String msg) {
        return ResponseEntity.status(status).body(Map.of("success", false, "error", msg));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }
}
