package com.grassland.identity.identityprofile;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.organization.CurrentAccountResolver;
import com.grassland.identity.session.SessionRepository;
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
 *   <li>GET /api/me/sessions — 列当前账号所有 session（设备/标签）：device_label/ip/last_seen/active_identity，
 *       并用 {@code current} 标出发起本次请求的那台设备（前端据此提示「撤销自己会登出」）。</li>
 *   <li>DELETE /api/me/sessions/{sessionToken} — 撤销该 session：清活动身份 + <b>删除登录会话（该设备真正登出）</b>
 *       + 审计 {@code revoke_session}；撤销他人 session → 403；不存在 → 404。</li>
 * </ul>
 *
 * <p>设备清单以 legacy {@code session} 表（登录即有行）为准，左连 {@code identity_session} 补活动身份/设备信息——
 * 后者是懒创建的，只按它列会漏掉「登录了但没切过身份」的设备，而在安全界面里给出一个看起来完整的子集
 * 比不做更危险。
 */
@RestController
public class IdentitySessionController {

    private final CurrentAccountResolver accounts;
    private final IdentitySessionRepository sessions;
    private final IdentityAuditLogRepository audit;
    private final SessionRepository loginSessions;

    public IdentitySessionController(CurrentAccountResolver accounts, IdentitySessionRepository sessions,
                                     IdentityAuditLogRepository audit, SessionRepository loginSessions) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.audit = audit;
        this.loginSessions = loginSessions;
    }

    @GetMapping("/api/me/sessions")
    public Mono<ResponseEntity<Map<String, Object>>> list(ServerHttpRequest request) {
        return accounts.resolvePrincipal(request)
                .flatMap(principal -> sessions.findLoginSessionsByAccount(principal.user().id()).collectList()
                        .map(list -> ResponseEntity.ok(Map.of("success", true,
                                "data", list.stream()
                                        .map(s -> toBody(s, s.sessionToken().equals(principal.sid())))
                                        .toList()))));
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
                            // 顺序：先清活动身份，再删登录会话（这一步才让那台设备真正登出），最后审计。
                            return sessions.deleteByToken(sessionToken)
                                    .then(loginSessions.deleteSession(sessionToken))
                                    .then(audit.append(IdentityAuditAction.REVOKE_SESSION, account.id(),
                                            fromType, null, sessionToken, fp.deviceId(), fp.ipAddress(), fp.userAgent()))
                                    .thenReturn(ResponseEntity.ok(Map.<String, Object>of("success", true)));
                        }));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    private Map<String, Object> toBody(IdentitySession s, boolean current) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionToken", s.sessionToken());
        m.put("activeIdentityType", s.activeIdentityType());
        m.put("deviceId", s.deviceId());
        m.put("deviceLabel", s.deviceLabel());
        m.put("ipAddress", s.ipAddress());
        m.put("lastSeenAt", s.lastSeenAt() == null ? null : s.lastSeenAt().toString());
        m.put("current", current);
        return m;
    }
}
