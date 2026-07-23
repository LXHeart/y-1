package com.grassland.identity.identityprofile;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.organization.CurrentAccountResolver;
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
 * 多设备 session 视图与撤销 HTTP 入口。草场身份域 Slice 2I（HLD D-08 多设备）。
 *
 * <ul>
 *   <li>GET /api/me/sessions — 列当前账号所有 session（设备/标签）：device_label/ip/last_seen/active_identity。</li>
 *   <li>DELETE /api/me/sessions/{sessionToken} — 撤销该 session（注销该设备活动身份）+ 审计 {@code revoke_session}；
 *       撤销他人 session → 403；不存在 → 404。</li>
 * </ul>
 *
 * <p>仅列出「有过身份活动」的 session（identity_session 懒创建）；完整设备清单待 identity 自管会话时补。
 */
@RestController
public class IdentitySessionController {

    private final CurrentAccountResolver accounts;
    private final IdentitySessionRepository sessions;
    private final IdentityAuditLogRepository audit;

    public IdentitySessionController(CurrentAccountResolver accounts, IdentitySessionRepository sessions,
                                     IdentityAuditLogRepository audit) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.audit = audit;
    }

    @GetMapping("/api/me/sessions")
    public Mono<ResponseEntity<Map<String, Object>>> list(ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(account -> sessions.findByAccount(account.id()).collectList()
                        .map(list -> ResponseEntity.ok(Map.of("success", true,
                                "data", list.stream().map(this::toBody).toList()))));
    }

    @DeleteMapping("/api/me/sessions/{sessionToken}")
    public Mono<ResponseEntity<Map<String, Object>>> revoke(@PathVariable String sessionToken, ServerHttpRequest request) {
        DeviceFingerprint fp = DeviceFingerprint.from(request);
        return accounts.resolve(request)
                .flatMap(account -> sessions.findByToken(sessionToken)
                        .switchIfEmpty(Mono.error(new IdentityException(404, "session 不存在")))
                        .flatMap(target -> {
                            if (!target.accountId().equals(account.id())) {
                                return Mono.error(new IdentityException(403, "无权撤销他人 session"));
                            }
                            String fromType = target.activeIdentityType();
                            return sessions.deleteByToken(sessionToken)
                                    .then(audit.append(IdentityAuditAction.REVOKE_SESSION, account.id(),
                                            fromType, null, sessionToken, fp.deviceId(), fp.ipAddress(), fp.userAgent()))
                                    .thenReturn(ResponseEntity.ok(Map.<String, Object>of("success", true)));
                        }));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    private Map<String, Object> toBody(IdentitySession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionToken", s.sessionToken());
        m.put("activeIdentityType", s.activeIdentityType());
        m.put("deviceId", s.deviceId());
        m.put("deviceLabel", s.deviceLabel());
        m.put("ipAddress", s.ipAddress());
        m.put("lastSeenAt", s.lastSeenAt() == null ? null : s.lastSeenAt().toString());
        return m;
    }
}
