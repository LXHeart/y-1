package com.grassland.identity.identityprofile;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.organization.CurrentAccountResolver;
import com.grassland.identity.security.PasswordVerifier;
import com.grassland.identity.user.UserLookup;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 重认证（MFA，HLD §11.2「客服覆盖判决须重新认证」）。
 *
 * <p><b>解决的问题</b>：trust 的 {@code final-decision} 要求断言 {@code reauthenticatedAt} 在 5 分钟内，
 * 但此前登录链路不产生该字段（edge-bff 签发时硬编码 null）→ 客服终审恒 403。
 *
 * <p>链路：本端点校验密码 → 写 {@code identity_session.reauthenticated_at/auth_strength}
 * → edge-bff 直读 session 时取出 → 签进断言 → trust 校验近期性。
 *
 * <p><b>按 session 记录</b>（非按账号）：一个设备重认证不提升另一设备的权限，与活动身份同粒度。
 *
 * <p><b>本轮范围</b>：以密码作为第二因子（re-auth by password）。真正的多因子（TOTP / 短信码）
 * 需先有因子注册与管理，属后续；此处先打通「近期性证明」这条链路，端点契约不变。
 */
@RestController
public class ReauthenticationController {

    private final CurrentAccountResolver accounts;
    private final UserLookup users;
    private final PasswordVerifier passwords;
    private final IdentitySessionRepository sessions;

    public ReauthenticationController(CurrentAccountResolver accounts, UserLookup users,
                                      PasswordVerifier passwords, IdentitySessionRepository sessions) {
        this.accounts = accounts;
        this.users = users;
        this.passwords = passwords;
        this.sessions = sessions;
    }

    @PostMapping("/api/me/reauthenticate")
    public Mono<ResponseEntity<Map<String, Object>>> reauthenticate(@RequestBody ReauthenticateRequest body,
                                                                     ServerHttpRequest request) {
        return accounts.resolvePrincipal(request)
                .flatMap(principal -> users.findLoginUserById(principal.user().id())
                        .switchIfEmpty(Mono.error(new IdentityException(401, "未登录")))
                        .flatMap(user -> {
                            if (!passwords.verify(body.password(), user.passwordHash())) {
                                // 不区分「密码错」与其它失败，避免成为密码探测口
                                return Mono.error(new IdentityException(403, "重认证失败"));
                            }
                            return sessions.markReauthenticated(principal.sid(), user.id());
                        }))
                .map(session -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("authStrength", session.authStrength());
                    data.put("reauthenticatedAt",
                            session.reauthenticatedAt() == null ? null : session.reauthenticatedAt().toString());
                    return ResponseEntity.ok(Map.of("success", true, "data", data));
                });
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status())
                .body(Map.of("success", false, "error", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadInput(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "error", error.getMessage()));
    }
}
