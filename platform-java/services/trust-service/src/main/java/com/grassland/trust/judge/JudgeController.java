package com.grassland.trust.judge;

import com.grassland.trust.security.TrustCallerResolver;
import com.grassland.trust.security.TrustException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 审判官池入口（草场 HLD 3.1「符合条件的推荐官」）。解 Slice 6C 遗留缺口——
 * 此前 {@code judge} 表只能手工插入，无 HTTP 入口，导致 {@code adjudicate} 恒返回 503。
 *
 * <ul>
 *   <li>POST /api/trust/judges — 推荐官自主报名入池（幂等：重复报名 / 退池后再报名均复活同一行）。</li>
 *   <li>GET /api/trust/judges/me — 查本人入池状态（未入池 → 404）。</li>
 *   <li>DELETE /api/trust/judges/me — 退池（软删 active=false，保留历史面板/投票完整性）。</li>
 * </ul>
 *
 * <p>仅推荐官可入池（{@code requireJudge} 是投票门禁，此处用 {@code requireMerchantOrRecommender} 后再筛
 * recommender——商家不得自任审判官，避免既当运动员又当裁判）。
 * GL-P2-TRUST-001：入池时从 marketplace 获取声誉等级，映射为 eligibility_tier（Lv1=1, Lv2=2, ..., Lv5=5）。
 */
@RestController
public class JudgeController {

    private static final Logger log = LoggerFactory.getLogger(JudgeController.class);

    private final TrustCallerResolver callers;
    private final JudgeRepository judges;
    private final MarketplaceReputationClient reputationClient;

    public JudgeController(TrustCallerResolver callers, JudgeRepository judges,
                          MarketplaceReputationClient reputationClient) {
        this.callers = callers;
        this.judges = judges;
        this.reputationClient = reputationClient;
    }

    @PostMapping("/api/trust/judges")
    public Mono<ResponseEntity<Map<String, Object>>> enroll(ServerHttpRequest request) {
        return callers.requireMerchantOrRecommender(request)
                .filter(TrustCallerResolver.Caller::isRecommender)
                .switchIfEmpty(fail(403, "仅推荐官可报名成为审判官"))
                .flatMap(caller -> reputationClient.getLevel(caller.accountId())
                        // 声誉查询失败时回退到 tier=1（允许入池，但不获得高 tier 权限）
                        .onErrorResume(e -> {
                            log.warn("Failed to fetch reputation for {}, defaulting to tier=1: {}",
                                    caller.accountId(), e.getMessage());
                            return Mono.just(new MarketplaceReputationClient.LevelResult(caller.accountId(), 1));
                        })
                        .flatMap(level -> judges.enrollWithTier(
                                caller.accountId(), caller.organizationId(), level.level())))
                .map(judge -> ResponseEntity.ok(Map.of("success", true, "data", toBody(judge))));
    }

    @GetMapping("/api/trust/judges/me")
    public Mono<ResponseEntity<Map<String, Object>>> me(ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> judges.findByAccountId(caller.accountId())
                        .switchIfEmpty(fail(404, "尚未加入审判官池"))
                        .map(judge -> ResponseEntity.ok(Map.of("success", true, "data", toBody(judge)))));
    }

    @DeleteMapping("/api/trust/judges/me")
    public Mono<ResponseEntity<Map<String, Object>>> leave(ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> judges.deactivate(caller.accountId())
                        .switchIfEmpty(fail(404, "尚未加入审判官池"))
                        .map(judge -> ResponseEntity.ok(Map.of("success", true, "data", toBody(judge)))));
    }

    private Map<String, Object> toBody(Judge judge) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", judge.id());
        m.put("accountId", judge.accountId());
        m.put("organizationId", judge.organizationId());
        m.put("eligibilityTier", judge.eligibilityTier());
        m.put("active", judge.active());
        m.put("createdAt", judge.createdAt() == null ? null : judge.createdAt().toString());
        return m;
    }

    private static <T> Mono<T> fail(int status, String message) {
        return Mono.error(new TrustException(status, message));
    }
}
