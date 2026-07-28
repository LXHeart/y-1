package com.grassland.trust.dispute;

import com.grassland.trust.security.TrustCallerResolver;
import com.grassland.trust.security.TrustException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Service-only authoritative dispute resolution read for settlement reconciliation. */
@RestController
public class DisputeResolutionController {

    private final DisputeCaseRepository disputes;
    private final TrustCallerResolver callers;

    public DisputeResolutionController(
            DisputeCaseRepository disputes, TrustCallerResolver callers) {
        this.disputes = disputes;
        this.callers = callers;
    }

    @GetMapping("/api/trust/disputes/{id}/resolution")
    public Mono<ResponseEntity<Map<String, Object>>> resolution(
            @PathVariable String id, ServerHttpRequest request) {
        return disputes.findById(id)
                .switchIfEmpty(Mono.error(new TrustException(404, "争议不存在")))
                .flatMap(dispute -> callers.requireServiceForOrg(
                                request,
                                dispute.organizationId(),
                                TrustCallerResolver.MARKETPLACE_SERVICE)
                        .thenReturn(dispute))
                .filter(dispute -> "final".equals(dispute.status()))
                .switchIfEmpty(Mono.error(new TrustException(409, "争议尚未终局")))
                .map(dispute -> ResponseEntity.ok(Map.of(
                        "success", true,
                        "data", toBody(dispute))));
    }

    private static Map<String, Object> toBody(DisputeCase dispute) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("disputeId", dispute.id());
        body.put("engagementRef", dispute.engagementRef());
        body.put("organizationId", dispute.organizationId());
        body.put("status", dispute.status());
        body.put("finalDecision", dispute.finalDecision());
        body.put("version", dispute.version());
        body.put("updatedAt", dispute.updatedAt() == null
                ? null
                : dispute.updatedAt().toString());
        return body;
    }
}
