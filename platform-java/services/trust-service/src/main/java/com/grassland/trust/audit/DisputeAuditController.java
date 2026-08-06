package com.grassland.trust.audit;

import com.grassland.identity.assertion.BackendRole;
import com.grassland.trust.security.TrustCallerResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 争议审计查询（GL-P2-ADMIN-009 审计部分）。
 *
 * <p>把已有的 {@link DisputeAuditRepository#listByDispute} 挂到 HTTP 端点——此前该方法定义了但 0 调用方。
 * 门闩 {@code requireRole(CUSTOMER_SERVICE)}（PLATFORM_ADMIN 超集）。只读。
 */
@RestController
public class DisputeAuditController {

    private final DisputeAuditRepository audits;
    private final TrustCallerResolver callers;

    public DisputeAuditController(DisputeAuditRepository audits, TrustCallerResolver callers) {
        this.audits = audits;
        this.callers = callers;
    }

    /** 某争议的完整审计时间线（按发生顺序）。 */
    @GetMapping("/api/trust/disputes/{id}/audit")
    public Mono<ResponseEntity<Map<String, Object>>> disputeAudit(
            @PathVariable String id, ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.CUSTOMER_SERVICE)
                .thenMany(audits.listByDispute(id).map(DisputeAuditController::toBody))
                .collectList()
                .map(items -> ResponseEntity.ok(Map.of("success", true, "data", items)));
    }

    private static Map<String, Object> toBody(DisputeAudit audit) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", audit.id());
        map.put("disputeId", audit.disputeId());
        map.put("action", audit.action());
        map.put("actorAccountId", audit.actorAccountId());
        map.put("actorRole", audit.actorRole());
        map.put("note", audit.note());
        map.put("createdAt", audit.createdAt() == null ? null : audit.createdAt().toString());
        return map;
    }
}
