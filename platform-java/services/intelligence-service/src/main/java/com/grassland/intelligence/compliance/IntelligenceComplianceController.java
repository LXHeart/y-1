package com.grassland.intelligence.compliance;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class IntelligenceComplianceController {

    private final IntelligenceCallerResolver callers;
    private final IntelligenceComplianceRepository repository;

    public IntelligenceComplianceController(
            IntelligenceCallerResolver callers, IntelligenceComplianceRepository repository) {
        this.callers = callers;
        this.repository = repository;
    }

    @GetMapping("/internal/compliance/accounts/{accountId}/closure-check")
    public Mono<ResponseEntity<Map<String, Object>>> closureCheck(
            @PathVariable String accountId, ServerHttpRequest request) {
        return callers.requireServicePrincipal(request, IntelligenceCallerResolver.IDENTITY_SERVICE)
                .then(repository.activeJobCount(accountId))
                .map(count -> {
                    List<Map<String, Object>> blockers = new ArrayList<>();
                    if (count > 0) {
                        blockers.add(blocker("RUNNING_AI_JOB", "仍有执行中或待补偿的 AI 任务", count));
                    }
                    return ResponseEntity.ok(success(Map.of("blockers", blockers)));
                });
    }

    @PostMapping("/internal/compliance/accounts/{accountId}/erase")
    public Mono<ResponseEntity<Map<String, Object>>> erase(
            @PathVariable String accountId, ServerHttpRequest request) {
        return callers.requireServicePrincipal(request, IntelligenceCallerResolver.IDENTITY_SERVICE)
                .then(repository.erasePii(accountId))
                .map(counts -> ResponseEntity.ok(success(Map.of(
                        "erased", true, "counts", counts,
                        "retained", List.of("ai_cost_runs", "billing_compensations")))));
    }

    private static Map<String, Object> blocker(String code, String message, long count) {
        Map<String, Object> blocker = new LinkedHashMap<>();
        blocker.put("domain", "intelligence");
        blocker.put("code", code);
        blocker.put("message", message);
        blocker.put("count", count);
        return blocker;
    }

    private static Map<String, Object> success(Map<String, Object> data) {
        return Map.of("success", true, "data", data);
    }
}
