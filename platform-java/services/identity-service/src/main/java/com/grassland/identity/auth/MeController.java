package com.grassland.identity.auth;

import com.grassland.identity.organization.CurrentAccountResolver;
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
 * 账号停用守卫（403）保留在本控制器（仅 /me 的展示策略，非所有受保护端点的统一策略）。
 */
@RestController
public class MeController {
    private final CurrentAccountResolver accounts;

    public MeController(CurrentAccountResolver accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/api/auth/me")
    public Mono<ResponseEntity<Map<String, Object>>> me(ServerHttpRequest request) {
        return accounts.resolve(request)
                .filter(AuthUser::isActive)
                .switchIfEmpty(Mono.error(new IdentityException(403, "当前账号不可用")))
                .map(this::toResponse);
    }

    private ResponseEntity<Map<String, Object>> toResponse(AuthUser user) {
        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("id", user.id());
        userInfo.put("email", user.email());
        if (user.displayName() != null && !user.displayName().isBlank()) {
            userInfo.put("displayName", user.displayName());
        }
        userInfo.put("role", user.role());
        return ResponseEntity.ok(Map.of("success", true, "data", Map.of("user", userInfo)));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status())
                .body(Map.of("success", false, "error", error.getMessage()));
    }
}
