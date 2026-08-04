package com.grassland.identity.mobile;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.identityprofile.DeviceFingerprint;
import com.grassland.identity.identityprofile.IdentityAuditAction;
import com.grassland.identity.identityprofile.IdentityAuditLogRepository;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * POST /api/auth/revoke（GL-P3-IDENTITY-001）：refresh token 自鉴权撤销。
 *
 * <p>body：{@code {"refresh_token": "...", "all_devices": true|false}}。
 * {@code all_devices=true} 撤销该 token 所属账号的全部活跃 token（token 本身即身份证明，无需其他鉴权）。
 * 撤销成功追加审计 {@code token_revoke}（登录/刷新不审计——避免每 15 分钟刷一条；撤销是安全事件必须留痕）。
 *
 * <p>设计文档 Phase 3 写的 DELETE 是笔误：DELETE 带 body 各端支持不一，统一 POST（§3.4 即 POST）。
 */
@RestController
public class RevokeController {

    private final RefreshTokenService service;
    private final IdentityAuditLogRepository audit;

    public RevokeController(RefreshTokenService service, IdentityAuditLogRepository audit) {
        this.service = service;
        this.audit = audit;
    }

    @PostMapping(value = "/api/auth/revoke", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> revoke(@RequestBody Mono<Map<String, Object>> bodyMono,
                                                            ServerHttpRequest request) {
        if (!service.isConfigured()) {
            return Mono.just(build503());
        }
        return bodyMono.switchIfEmpty(Mono.just(Map.of())).flatMap(body -> {
            String token = body.get("refresh_token") instanceof String s ? s : null;
            boolean allDevices = Boolean.TRUE.equals(body.get("all_devices"))
                    || "true".equalsIgnoreCase(String.valueOf(body.get("all_devices")));
            if (token == null || token.isBlank()) {
                return Mono.just(build401());
            }
            DeviceFingerprint fp = DeviceFingerprint.from(request);
            return service.refreshTokenAccountId(token)
                    .flatMap(accountId -> service.revoke(token, allDevices)
                            .flatMap(count -> audit.append(IdentityAuditAction.TOKEN_REVOKE, accountId,
                                            null, null, null, fp.deviceId(), fp.ipAddress(), fp.userAgent())
                                    .thenReturn(ResponseEntity.ok(Map.<String, Object>of("success", true,
                                            "data", Map.of("revoked", count)))))
                            .switchIfEmpty(Mono.just(build401())))
                    .switchIfEmpty(Mono.just(build401()));
        });
    }

    private ResponseEntity<Map<String, Object>> build401() {
        return ResponseEntity.status(401).body(Map.of("success", false, "error",
                "refresh token 无效或已过期，请重新登录。"));
    }

    private ResponseEntity<Map<String, Object>> build503() {
        return ResponseEntity.status(503).body(Map.of("success", false, "error",
                "移动端登录暂未启用"));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }
}
