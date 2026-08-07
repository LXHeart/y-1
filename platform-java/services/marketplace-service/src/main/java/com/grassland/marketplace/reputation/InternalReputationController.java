package com.grassland.marketplace.reputation;

import com.grassland.marketplace.security.MarketplaceCallerResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 声誉内部端点（供 trust 服务调用，GL-P2-TRUST-001：reputation-based judge eligibility）。
 *
 * <p>仅接受 {@code principal=trust} 的服务断言。返回推荐官的声誉等级代码（level），
 * trust 用于判定审判官资格（Lv5 绑定审判官资格）。
 *
 * <p>从未履约的账号也返回 200 + Lv1；非法 UUID 返回 400。
 */
@RestController
public class InternalReputationController {

    public static final String TRUST_SERVICE = "trust";
    public static final String INTELLIGENCE_SERVICE = "intelligence";

    private final MarketplaceCallerResolver callers;
    private final ReputationService reputations;

    public InternalReputationController(MarketplaceCallerResolver callers,
                                        ReputationService reputations) {
        this.callers = callers;
        this.reputations = reputations;
    }

    @GetMapping("/internal/marketplace/reputation/{accountId}/level")
    public Mono<ResponseEntity<Map<String, Object>>> getLevel(@PathVariable String accountId,
                                                                ServerHttpRequest request) {
        return callers.requireServicePrincipal(request, TRUST_SERVICE)
                .flatMap(service -> Mono.fromCallable(() -> requireUuid(accountId)))
                .flatMap(reputations::snapshot)
                .map(snapshot -> {
                    ReputationEvaluation evaluation = snapshot.evaluation();
                    RecommenderLevel level = evaluation.effectiveLevel();
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("accountId", snapshot.accountId());
                    data.put("level", level.code());
                    data.put("levelTitle", snapshot.policy().ruleFor(level).title());
                    data.put("effectiveLevel", level.code());
                    data.put("levelNumber", level.ordinal() + 1);
                    data.put("judgeEligible", evaluation.judgeEligible());
                    data.put("policyVersion", snapshot.policy().version());
                    return ResponseEntity.ok(Map.of("success", true, "data", data));
                });
    }

    /** AI free-quota entitlement snapshot; only intelligence may request it. */
    @GetMapping("/internal/marketplace/reputation/{accountId}/ai-entitlement")
    public Mono<ResponseEntity<Map<String, Object>>> getAiEntitlement(
            @PathVariable String accountId, ServerHttpRequest request) {
        return callers.requireServicePrincipal(request, INTELLIGENCE_SERVICE)
                .flatMap(service -> Mono.fromCallable(() -> requireUuid(accountId)))
                .flatMap(reputations::snapshot)
                .map(snapshot -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("accountId", snapshot.accountId());
                    data.put("aiQuotaMultiplierBps", snapshot.evaluation().aiQuotaMultiplierBps());
                    data.put("policyVersion", snapshot.policy().version());
                    return ResponseEntity.ok(Map.of("success", true, "data", data));
                });
    }

    /** 非法 UUID → 400。 */
    private static String requireUuid(String accountId) {
        return ReputationAdminController.requireUuid(accountId);
    }
}
