package com.grassland.marketplace.reputation;

import com.grassland.marketplace.security.MarketplaceCallerResolver;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 推荐官声誉 HTTP 入口（PRD 五 + 六「数据面板」）。
 *
 * <p>{@code GET /api/reputation/{accountId}} — 商家审核报名时看「他靠不靠谱」。与 identity 的画像端点
 * （{@code /api/recommenders/{accountId}/profile}，回答「他是谁」）配套：一个在 identity，一个在 marketplace，
 * 因为声誉的事实全在撮合库里，跨服务取数只会把一次查询变成一次 RPC。
 *
 * <p><b>可见性</b>：与画像同口径——任何登录用户可按 accountId 读，只回聚合指标，
 * 不回具体接了哪些任务、被谁评了几分（那是别人的经营信息）；也<b>没有「按条件搜人」的入口</b>，
 * 商家拿到 accountId 的唯一途径仍是对方主动报名。筛选发生在「我这个任务的报名者」这个集合内。
 */
@RestController
public class ReputationController {

    private final MarketplaceCallerResolver callers;
    private final ReputationService reputations;

    public ReputationController(MarketplaceCallerResolver callers, ReputationService reputations) {
        this.callers = callers;
        this.reputations = reputations;
    }

    @GetMapping("/api/reputation/{accountId}")
    public Mono<ResponseEntity<Map<String, Object>>> reputationOf(@PathVariable String accountId,
                                                                  ServerHttpRequest request) {
        return callers.resolve(request)
                .then(Mono.fromCallable(() -> requireUuid(accountId)))
                .flatMap(reputations::snapshot)
                .map(snapshot -> ResponseEntity.ok(Map.of("success", true,
                        "data", ReputationResponseMapper.reputation(snapshot, false))));
    }

    /** 非法 UUID 直接 400——否则 CAST(:acc AS uuid) 在库里炸成 500，错在调用方却报成服务端故障。 */
    private static String requireUuid(String accountId) {
        return ReputationAdminController.requireUuid(accountId);
    }
}
