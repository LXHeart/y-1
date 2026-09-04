package com.grassland.trust.judge;

import com.grassland.trust.security.TrustCallerResolver;
import com.grassland.trust.security.TrustException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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
 * GL-P2-TRUST-001：入池时严格依赖 marketplace 的有效 Lv5/judgeEligible 判定；上游失败不降级放行。
 */
@RestController
public class JudgeController {

    private final TrustCallerResolver callers;
    private final JudgeRepository judges;
    private final MarketplaceReputationClient reputationClient;
    private final IdentityOrganizationMembershipClient identityMemberships;
    private final JudgeExamService examService;

    public JudgeController(TrustCallerResolver callers, JudgeRepository judges,
                          MarketplaceReputationClient reputationClient,
                          IdentityOrganizationMembershipClient identityMemberships,
                          JudgeExamService examService) {
        this.callers = callers;
        this.judges = judges;
        this.reputationClient = reputationClient;
        this.identityMemberships = identityMemberships;
        this.examService = examService;
    }

    @PostMapping("/api/trust/judges")
    public Mono<ResponseEntity<Map<String, Object>>> enroll(ServerHttpRequest request) {
        return callers.requireMerchantOrRecommender(request)
                .filter(TrustCallerResolver.Caller::isRecommender)
                .switchIfEmpty(fail(403, "仅推荐官可报名成为审判官"))
                .flatMap(caller -> reputationClient.getLevel(caller.accountId())
                        .onErrorMap(error -> new TrustException(503, "声誉服务暂时不可用"))
                        // 任务书 #74 卡 E（D4）：Lv5 直入；Lv4 须完成 ≥20 任务方可报名（考试在报名后另行把关）。
                        .filter(level -> level.levelNumber() >= 4)
                        .switchIfEmpty(fail(403, "仅有效 Lv4/Lv5 推荐官可报名成为审判官"))
                        .flatMap(level -> meetsThreshold(caller.accountId(), level.levelNumber())
                                .switchIfEmpty(fail(403, "Lv4 推荐官须完成 ≥20 任务方可报名审判官"))
                                .flatMap(ok -> identityMemberships.organizationIds(caller.accountId())
                                        .onErrorMap(error -> new TrustException(503, "身份服务暂时不可用"))
                                        .flatMap(organizationIds -> judges.enrollWithTier(
                                                caller.accountId(), soleOrganizationId(organizationIds),
                                                level.levelNumber())))))
                .map(judge -> ResponseEntity.ok(Map.of("success", true, "data", toBody(judge))));
    }

    /** Lv4 报名门槛：完成 ≥20 任务（level 端点回带 completedCount；缺失按不满足 fail-closed）。 */
    private Mono<Boolean> meetsThreshold(String accountId, int levelNumber) {
        if (levelNumber >= 5) {
            return Mono.just(true);
        }
        return examService.meetsEnrollmentThreshold(accountId, levelNumber)
                .filter(Boolean::booleanValue)
                .switchIfEmpty(fail(403, "Lv4 推荐官须完成 ≥20 任务方可报名审判官"));
    }

    private static String soleOrganizationId(Set<String> organizationIds) {
        return organizationIds.size() == 1 ? organizationIds.iterator().next() : null;
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
        m.put("opsAdmitted", judge.opsAdmitted());
        m.put("version", judge.version());
        m.put("opsAdmittedAt", judge.opsAdmittedAt() == null ? null : judge.opsAdmittedAt().toString());
        m.put("opsAdmittedBy", judge.opsAdmittedBy());
        // 任务书 #74 卡 E：见习/正式标识 + 挂起状态（审判台展示）。
        m.put("admissionLevel", judge.admissionLevel() == null ? "full" : judge.admissionLevel());
        m.put("probation", judge.isProbation());
        m.put("examPassedAt", judge.examPassedAt() == null ? null : judge.examPassedAt().toString());
        m.put("suspendedNow", judge.suspendedNow());
        m.put("suspendedUntil", judge.suspendedUntil() == null ? null : judge.suspendedUntil().toString());
        m.put("createdAt", judge.createdAt() == null ? null : judge.createdAt().toString());
        return m;
    }

    private static <T> Mono<T> fail(int status, String message) {
        return Mono.error(new TrustException(status, message));
    }
}
