package com.grassland.identity.auth;

import com.grassland.identity.assertion.BackendRole;
import com.grassland.identity.organization.CurrentAccountResolver;
import com.grassland.identity.user.AccountFlagRepository;
import com.grassland.identity.user.AuthUser;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * GET /api/auth/me — 返回当前登录账号。
 *
 * <p>身份解析委托 {@link CurrentAccountResolver}（Slice 2K 起 assertion 优先、cookie 回退），
 * 故 /api/auth/me 也能消费 edge-bff 签发的 {@code X-Grassland-Identity} 断言——不再各自重复 cookie 解析逻辑。
 * 账号停用守卫由 {@link CurrentAccountResolver} 统一执行，所有受保护端点口径一致。
 */
@RestController
public class MeController {
    private final CurrentAccountResolver accounts;
    private final AccountFlagRepository accountFlags;

    public MeController(CurrentAccountResolver accounts, AccountFlagRepository accountFlags) {
        this.accounts = accounts;
        this.accountFlags = accountFlags;
    }

    @GetMapping("/api/auth/me")
    public Mono<ResponseEntity<Map<String, Object>>> me(ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(this::toResponse);
    }

    private Mono<ResponseEntity<Map<String, Object>>> toResponse(AuthUser user) {
        Mono<Boolean> mustChange = accountFlags.mustChangePassword(user.id()).defaultIfEmpty(Boolean.FALSE);
        return mustChange.flatMap(flag -> accounts.resolveBackendRoles(user.id())
                .defaultIfEmpty(java.util.Set.of())
                .map(roles -> {
                    Map<String, Object> userInfo = new LinkedHashMap<>();
                    userInfo.put("id", user.id());
                    userInfo.put("email", user.email());
                    if (user.displayName() != null && !user.displayName().isBlank()) {
                        userInfo.put("displayName", user.displayName());
                    }
                    userInfo.put("role", user.role());
                    // GL-P2-ADMIN-001：后端角色数组（多值），供前端动态渲染后台入口
                    userInfo.put("roles", roles.stream()
                            .map(BackendRole::dbValue)
                            .sorted()
                            .toList());
                    // 任务书 #48：管理员代建后的首登强制改密态；前端据此路由锁到改密页
                    userInfo.put("mustChangePassword", Boolean.TRUE.equals(flag));
                    return ResponseEntity.ok(Map.of("success", true, "data", Map.of("user", userInfo)));
                }));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status())
                .body(Map.of("success", false, "error", error.getMessage()));
    }
}
