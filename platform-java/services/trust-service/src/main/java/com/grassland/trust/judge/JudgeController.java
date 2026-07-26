package com.grassland.trust.judge;

import com.grassland.trust.security.TrustCallerResolver;
import com.grassland.trust.security.TrustException;
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
 * {@code eligibilityTier} 固定 1：声誉模块未建，资格阈值由 {@code trust.adjudication.judge-eligibility-tier} 占位。
 */
@RestController
public class JudgeController {

    private final TrustCallerResolver callers;
    private final JudgeRepository judges;

    public JudgeController(TrustCallerResolver callers, JudgeRepository judges) {
        this.callers = callers;
        this.judges = judges;
    }

    @PostMapping("/api/trust/judges")
    public Mono<ResponseEntity<Map<String, Object>>> enroll(ServerHttpRequest request) {
        return callers.requireMerchantOrRecommender(request)
                .filter(TrustCallerResolver.Caller::isRecommender)
                .switchIfEmpty(fail(403, "仅推荐官可报名成为审判官"))
                .flatMap(caller -> judges.enroll(caller.accountId(), caller.organizationId()))
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
