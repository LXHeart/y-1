package com.grassland.trust.compliance;

import com.grassland.trust.security.TrustCallerResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class TrustComplianceController {

    private final TrustCallerResolver callers;
    private final TrustComplianceRepository repository;

    public TrustComplianceController(TrustCallerResolver callers, TrustComplianceRepository repository) {
        this.callers = callers;
        this.repository = repository;
    }

    @PostMapping("/internal/compliance/accounts/{accountId}/closure-check")
    public Mono<ResponseEntity<Map<String, Object>>> closureCheck(
            @PathVariable String accountId,
            @RequestBody ClosureCheckRequest body,
            ServerHttpRequest request) {
        List<String> refs = body == null || body.engagementRefs() == null ? List.of() : body.engagementRefs();
        return callers.requireService(request, TrustCallerResolver.IDENTITY_SERVICE)
                .then(repository.activeDisputeCount(accountId, refs))
                .map(count -> {
                    List<Map<String, Object>> blockers = new ArrayList<>();
                    if (count > 0) {
                        blockers.add(blocker("OPEN_DISPUTE", "仍有未结争议或上诉", count));
                    }
                    return ResponseEntity.ok(success(Map.of("blockers", blockers)));
                });
    }

    @PostMapping("/internal/compliance/accounts/{accountId}/erase")
    public Mono<ResponseEntity<Map<String, Object>>> erase(
            @PathVariable String accountId, ServerHttpRequest request) {
        return callers.requireService(request, TrustCallerResolver.IDENTITY_SERVICE)
                .thenReturn(ResponseEntity.ok(success(Map.of(
                        "erased", true,
                        "retained", List.of("disputes", "evidence", "adjudication", "audit")))));
    }

    private static Map<String, Object> blocker(String code, String message, long count) {
        Map<String, Object> blocker = new LinkedHashMap<>();
        blocker.put("domain", "trust");
        blocker.put("code", code);
        blocker.put("message", message);
        blocker.put("count", count);
        return blocker;
    }

    private static Map<String, Object> success(Map<String, Object> data) {
        return Map.of("success", true, "data", data);
    }

    public record ClosureCheckRequest(String accountId, List<String> engagementRefs) {}
}
