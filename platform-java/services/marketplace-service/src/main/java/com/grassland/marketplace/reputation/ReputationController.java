package com.grassland.marketplace.reputation;

import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
    private final ReputationRepository reputations;

    public ReputationController(MarketplaceCallerResolver callers, ReputationRepository reputations) {
        this.callers = callers;
        this.reputations = reputations;
    }

    @GetMapping("/api/reputation/{accountId}")
    public Mono<ResponseEntity<Map<String, Object>>> reputationOf(@PathVariable String accountId,
                                                                  ServerHttpRequest request) {
        return callers.resolve(request)
                .then(Mono.fromCallable(() -> requireUuid(accountId)))
                .flatMap(reputations::statsOf)
                .map(stats -> ResponseEntity.ok(Map.of("success", true, "data", toBody(accountId, stats))));
    }

    /** 非法 UUID 直接 400——否则 CAST(:acc AS uuid) 在库里炸成 500，错在调用方却报成服务端故障。 */
    private static String requireUuid(String accountId) {
        try {
            return UUID.fromString(accountId).toString();
        } catch (IllegalArgumentException invalid) {
            throw new MarketplaceException(400, "accountId 不是合法的账号标识");
        }
    }

    private static Map<String, Object> toBody(String accountId, ReputationStats stats) {
        RecommenderLevel level = RecommenderLevelPolicy.levelOf(stats);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("accountId", accountId);
        map.put("level", level.code());
        map.put("levelTitle", level.title());
        map.put("acceptedCount", stats.acceptedCount());
        map.put("completedCount", stats.completedCount());
        map.put("completionRate", round(stats.completionRate(), 4));
        map.put("ratingCount", stats.ratingCount());
        // 无评分 → null（不是 0）：「没人评过」与「口碑差」在 UI 上必须是两种说法
        map.put("averageScore", stats.averageScore() == null ? null : round(stats.averageScore(), 2));
        map.put("averageResponseSeconds", stats.averageResponseSeconds() == null
                ? null : Math.round(stats.averageResponseSeconds()));
        return map;
    }

    private static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }
}
