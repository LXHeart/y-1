package com.grassland.identity.mobile;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.identityprofile.DeviceFingerprint;
import com.grassland.identity.identityprofile.IdentityAuditAction;
import com.grassland.identity.identityprofile.IdentityAuditLogRepository;
import com.grassland.identity.organization.CurrentAccountResolver;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 移动端设备清单与撤销（GL-P3-IDENTITY-001，设计文档 §3.6）：
 *
 * <ul>
 *   <li>GET /api/me/devices — 列当前账号活跃 refresh token（= 已登录的移动设备）。
 *       {@code current} 标出本次请求所用的设备（Bearer access token 的 session_token == 行 id；cookie 会话恒 false）。</li>
 *   <li>DELETE /api/me/devices/{id} — 撤销该设备（软删 + 清 identity_session 活动身份，该设备真正登出）；
 *       跨账号 → 403，不存在 → 404，成功 → 审计 {@code device_revoke}。</li>
 * </ul>
 *
 * <p>鉴权走 {@link CurrentAccountResolver#resolvePrincipal}：断言优先（Bearer → edge 签发）、cookie 回退——
 * 本端点无需区分两种来路。与 {@code GET /api/me/sessions}（Web cookie 会话视图）互补：移动端不在 session 表里，
 * 其设备视图以 refresh_token 表为准。
 */
@RestController
public class DeviceController {

    private final CurrentAccountResolver accounts;
    private final RefreshTokenService service;
    private final IdentityAuditLogRepository audit;

    public DeviceController(CurrentAccountResolver accounts, RefreshTokenService service,
                            IdentityAuditLogRepository audit) {
        this.accounts = accounts;
        this.service = service;
        this.audit = audit;
    }

    @GetMapping("/api/me/devices")
    public Mono<ResponseEntity<Map<String, Object>>> list(ServerHttpRequest request) {
        return accounts.resolvePrincipal(request)
                .flatMap(principal -> service.listActiveDevices(principal.user().id())
                        .map(token -> toBody(token, token.id().equals(principal.sid())))
                        .collectList()
                        .map(devices -> ResponseEntity.ok(Map.<String, Object>of(
                                "success", true, "data", Map.of("devices", devices)))));
    }

    @DeleteMapping("/api/me/devices/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> revoke(@PathVariable String id, ServerHttpRequest request) {
        DeviceFingerprint fp = DeviceFingerprint.from(request);
        return accounts.resolve(request)
                .flatMap(account -> service.findDeviceById(id)
                        .switchIfEmpty(Mono.error(new IdentityException(404, "设备不存在")))
                        .flatMap(token -> {
                            if (!token.accountId().equals(account.id())) {
                                return Mono.error(new IdentityException(403, "无权撤销他人设备"));
                            }
                            return service.revokeDeviceById(id, account.id())
                                    .flatMap(count -> audit.append(IdentityAuditAction.DEVICE_REVOKE, account.id(),
                                                    null, null, id, fp.deviceId(), fp.ipAddress(), fp.userAgent())
                                            .thenReturn(ResponseEntity.ok(Map.<String, Object>of("success", true))));
                        }));
    }

    private static Map<String, Object> toBody(RefreshToken token, boolean current) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", token.id());
        body.put("device_name", token.deviceName());
        body.put("device_fingerprint", token.deviceFingerprint());
        body.put("last_used_at", format(token.lastUsedAt()));
        body.put("created_at", format(token.createdAt()));
        body.put("expires_at", format(token.expiresAt()));
        body.put("current", current);
        return body;
    }

    private static String format(java.time.Instant value) {
        return value == null ? null : DateTimeFormatter.ISO_INSTANT.format(value);
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }
}
