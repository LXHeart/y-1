package com.grassland.marketplace.reputation;

import com.grassland.identity.assertion.BackendRole;
import com.grassland.marketplace.security.MarketplaceCallerResolver;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 平台管理员的等级策略与 Lv5 邀请入口。 */
@RestController
public class ReputationAdminController {

    private final MarketplaceCallerResolver callers;
    private final ReputationPolicyRepository policies;
    private final ReputationService reputations;
    private final ReputationAdministrationService administration;

    public ReputationAdminController(
            MarketplaceCallerResolver callers,
            ReputationPolicyRepository policies,
            ReputationService reputations,
            ReputationAdministrationService administration) {
        this.callers = callers;
        this.policies = policies;
        this.reputations = reputations;
        this.administration = administration;
    }

    @GetMapping("/api/admin/reputation-config")
    public Mono<ResponseEntity<Map<String, Object>>> getConfiguration(ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.PLATFORM_ADMIN)
                .then(policies.findCurrent())
                .map(policy -> ok(ReputationResponseMapper.policy(policy)));
    }

    @PutMapping(value = "/api/admin/reputation-config", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> updateConfiguration(
            @RequestBody UpdateReputationPolicyRequest body, ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.PLATFORM_ADMIN)
                .flatMap(actor -> administration.updatePolicy(body, actor))
                .map(policy -> ok(ReputationResponseMapper.policy(policy)));
    }

    @GetMapping("/api/admin/reputation/{accountId}")
    public Mono<ResponseEntity<Map<String, Object>>> getReputation(
            @PathVariable String accountId, ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.PLATFORM_ADMIN)
                .then(Mono.fromCallable(() -> requireUuid(accountId)))
                .flatMap(reputations::snapshot)
                .map(snapshot -> ok(ReputationResponseMapper.reputation(snapshot, true)));
    }

    @PutMapping(value = "/api/admin/reputation/{accountId}/lv5-admission",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> updateLv5Admission(
            @PathVariable String accountId, @RequestBody UpdateLv5AdmissionRequest body,
            ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.PLATFORM_ADMIN)
                .flatMap(actor -> Mono.fromCallable(() -> requireUuid(accountId))
                        .flatMap(id -> administration.updateAdmission(id, body, actor)))
                .map(admission -> ok(ReputationResponseMapper.admission(admission)));
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    static String requireUuid(String accountId) {
        try {
            return UUID.fromString(accountId).toString();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("accountId 不是合法的账号标识");
        }
    }
}
