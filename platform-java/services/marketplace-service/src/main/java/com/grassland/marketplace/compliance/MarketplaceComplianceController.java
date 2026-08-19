package com.grassland.marketplace.compliance;

import com.grassland.marketplace.security.MarketplaceCallerResolver;
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
public class MarketplaceComplianceController {

    private static final String IDENTITY_SERVICE = "identity";

    private final MarketplaceCallerResolver callers;
    private final MarketplaceComplianceRepository repository;

    public MarketplaceComplianceController(
            MarketplaceCallerResolver callers, MarketplaceComplianceRepository repository) {
        this.callers = callers;
        this.repository = repository;
    }

    @GetMapping("/internal/compliance/accounts/{accountId}/closure-check")
    public Mono<ResponseEntity<Map<String, Object>>> closureCheck(
            @PathVariable String accountId, ServerHttpRequest request) {
        return callers.requireServicePrincipal(request, IDENTITY_SERVICE)
                .then(Mono.zip(repository.closureSummary(accountId),
                        repository.activeEngagementRefs(accountId).collectList()))
                .map(tuple -> {
                    List<Map<String, Object>> blockers = new ArrayList<>();
                    if (tuple.getT1().activeEngagements() > 0) {
                        blockers.add(blocker("PENDING_ENGAGEMENT", "仍有未完成的报名或履约",
                                tuple.getT1().activeEngagements()));
                    }
                    if (tuple.getT1().activeOrders() > 0) {
                        blockers.add(blocker("PENDING_ORDER", "仍有未完成的订单或售后",
                                tuple.getT1().activeOrders()));
                    }
                    return ResponseEntity.ok(success(Map.of(
                            "blockers", blockers,
                            "engagementRefs", tuple.getT2())));
                });
    }

    @PostMapping("/internal/compliance/accounts/{accountId}/erase")
    public Mono<ResponseEntity<Map<String, Object>>> erase(
            @PathVariable String accountId, ServerHttpRequest request) {
        return callers.requireServicePrincipal(request, IDENTITY_SERVICE)
                .then(repository.erasePii(accountId))
                .map(counts -> ResponseEntity.ok(success(Map.of(
                        "erased", true, "counts", counts,
                        "retained", List.of("tasks", "engagement_facts", "orders")))));
    }

    private static Map<String, Object> blocker(String code, String message, long count) {
        Map<String, Object> blocker = new LinkedHashMap<>();
        blocker.put("domain", "marketplace");
        blocker.put("code", code);
        blocker.put("message", message);
        blocker.put("count", count);
        return blocker;
    }

    private static Map<String, Object> success(Map<String, Object> data) {
        return Map.of("success", true, "data", data);
    }
}
