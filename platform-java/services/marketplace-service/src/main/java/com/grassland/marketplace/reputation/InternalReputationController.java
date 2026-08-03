package com.grassland.marketplace.reputation;

import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
 * <p>状态：200 存在；404 账号无声誉记录（未接任务）；400 非法 UUID。
 */
@RestController
public class InternalReputationController {

    public static final String TRUST_SERVICE = "trust";

    private final MarketplaceCallerResolver callers;
    private final ReputationRepository reputations;

    public InternalReputationController(MarketplaceCallerResolver callers,
                                        ReputationRepository reputations) {
        this.callers = callers;
        this.reputations = reputations;
    }

    @GetMapping("/internal/marketplace/reputation/{accountId}/level")
    public Mono<ResponseEntity<Map<String, Object>>> getLevel(@PathVariable String accountId,
                                                                ServerHttpRequest request) {
        return callers.requireServicePrincipal(request, TRUST_SERVICE)
                .flatMap(service -> Mono.fromCallable(() -> requireUuid(accountId)))
                .flatMap(reputations::statsOf)
                .map(stats -> {
                    RecommenderLevel level = RecommenderLevelPolicy.levelOf(stats);
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("accountId", accountId);
                    data.put("level", level.code());
                    data.put("levelTitle", level.title());
                    return ResponseEntity.ok(Map.of("success", true, "data", data));
                })
                .switchIfEmpty(Mono.error(new MarketplaceException(404, "账号无声誉记录")));
    }

    /** 非法 UUID → 400。 */
    private static String requireUuid(String accountId) {
        try {
            return UUID.fromString(accountId).toString();
        } catch (IllegalArgumentException invalid) {
            throw new MarketplaceException(400, "accountId 不是合法的账号标识");
        }
    }

    @ExceptionHandler(MarketplaceException.class)
    public ResponseEntity<Map<String, Object>> handleError(MarketplaceException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }
}
