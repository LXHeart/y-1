package com.grassland.trust.audit;

import com.grassland.identity.assertion.BackendRole;
import com.grassland.trust.security.TrustCallerResolver;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 证据访问审计只读入口（D-10）：客服与平台管理员可按受限维度追查访问记录。 */
@RestController
public class EvidenceAccessAuditController {

    private final DisputeEvidenceAccessAuditRepository audits;
    private final TrustCallerResolver callers;

    public EvidenceAccessAuditController(
            DisputeEvidenceAccessAuditRepository audits, TrustCallerResolver callers) {
        this.audits = audits;
        this.callers = callers;
    }

    @GetMapping("/api/admin/trust/evidence-access-audits")
    public Mono<ResponseEntity<Map<String, Object>>> list(
            @RequestParam(required = false) String disputeId,
            @RequestParam(required = false) String evidenceId,
            @RequestParam(required = false) String viewerAccountId,
            @RequestParam(required = false) String viewerRole,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false, defaultValue = "100") int limit,
            ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.CUSTOMER_SERVICE, BackendRole.PLATFORM_ADMIN)
                .flatMapMany(ignored -> {
                    String normalizedDisputeId = optionalUuid(disputeId, "disputeId");
                    String normalizedEvidenceId = optionalUuid(evidenceId, "evidenceId");
                    String normalizedViewerId = optionalUuid(viewerAccountId, "viewerAccountId");
                    String normalizedRole = blankToNull(viewerRole);
                    if (normalizedRole != null && normalizedRole.length() > 32) {
                        throw new IllegalArgumentException("viewerRole 长度不能超过 32");
                    }
                    if (limit < 1 || limit > 200) {
                        throw new IllegalArgumentException("limit 须为 1-200");
                    }
                    if (from != null && to != null && from.isAfter(to)) {
                        throw new IllegalArgumentException("from 不能晚于 to");
                    }
                    return audits.list(normalizedDisputeId, normalizedEvidenceId, normalizedViewerId,
                            normalizedRole, from, to, limit).map(EvidenceAccessAuditController::toBody);
                })
                .collectList()
                .map(items -> ResponseEntity.ok(Map.of("success", true, "data", items,
                        "meta", Map.of("limit", limit))));
    }

    private static Map<String, Object> toBody(DisputeEvidenceAccessAudit audit) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", audit.id());
        item.put("evidenceId", audit.evidenceId());
        item.put("disputeId", audit.disputeId());
        item.put("viewerAccountId", audit.viewerAccountId());
        item.put("viewerRole", audit.viewerRole());
        item.put("purpose", audit.purpose());
        item.put("viewedAt", audit.viewedAt() == null ? null : audit.viewedAt().toString());
        return item;
    }

    private static String optionalUuid(String value, String field) {
        String normalized = blankToNull(value);
        if (normalized == null) return null;
        try {
            return UUID.fromString(normalized).toString();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(field + " 格式错误");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
