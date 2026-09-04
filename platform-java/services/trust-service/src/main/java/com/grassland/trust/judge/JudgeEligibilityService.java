package com.grassland.trust.judge;

import com.grassland.trust.security.TrustException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 抽签资格服务（GL-P2-TRUST-001 Lv5 实时复验 + 任务书 #74 卡 D 垂类硬配额 + 卡 E 见习席位约束）。
 *
 * <p>任务书 #74 资格规则（V14 触发器同口径）：Lv5 直入；Lv4+准入考试及格=见习审判官；挂起期排除。
 * 每个候选经 marketplace 声誉（等级/平台完成数）与 identity 组织归属实时复验，上游失败 fail-closed。
 */
@Component
public class JudgeEligibilityService {

    private static final int REVALIDATION_CONCURRENCY = 4;
    /** 抽分池所需的候选最低等级（卡 E：Lv4 见习通道的地板；Lv5 无条件可抽）。 */
    public static final int DRAW_MIN_TIER = 4;
    /** 单次抽签复验候选上限（防池子异常膨胀打爆上游）。 */
    private static final int MAX_CANDIDATES_VALIDATED = 200;

    private final JudgeRepository judges;
    private final MarketplaceReputationClient reputationClient;
    private final IdentityOrganizationMembershipClient identityMemberships;

    public JudgeEligibilityService(JudgeRepository judges, MarketplaceReputationClient reputationClient,
                                   IdentityOrganizationMembershipClient identityMemberships) {
        this.judges = judges;
        this.reputationClient = reputationClient;
        this.identityMemberships = identityMemberships;
    }

    /** 抽签结果：席次（含熟手标记）。 */
    public record PanelPick(Judge judge, boolean matchedPlatform) {
        public String accountId() {
            return judge.accountId();
        }
    }

    /**
     * 既有通用池抽签（GL-P2-TRUST-001 语义保留：无垂类配额、不足即 503）。
     * 复验谓词更新为 V14 规则（Lv5 直入 / Lv4+考试及格 / 未挂起）。
     */
    public Mono<List<Judge>> drawVerifiedPool(int minTier, String organizationId, int size) {
        return drawVerifiedPool(minTier, organizationId, size, Set.of());
    }

    /** 抽取新候选时先排除已经固化在当前面板中的账号，避免重复远端复验或补位不足。 */
    public Mono<List<Judge>> drawVerifiedPool(int minTier, String organizationId, int size,
                                              Set<String> excludedAccountIds) {
        return drawPicks(minTier, organizationId, size, null, 0, 0, 0, excludedAccountIds)
                .map(picks -> picks.stream().map(PanelPick::judge).toList());
    }

    /**
     * 任务书 #74 卡 D（D3 硬配额）+ 卡 E（D4 见习 ≤2 席）：
     * 先取「涉案平台完成 ≥platformCompletionsMin 的合格候选」填满 hardQuota 个熟手席（不足按可得数降级、
     * 不 503——派生 3），再从通用池补齐 size；见习席超过 probationSeats 时优先换 full 候选，
     * 池尽容忍超出（快照记 probationCount，不 503）。总候选不足 size 维持现状 503。
     *
     * @param taskPlatform 涉案平台；null/blank = 无平台信息，全部走通用池（存量行为）
     */
    public Mono<List<PanelPick>> drawVerifiedPanel(int size, String organizationId, String taskPlatform,
                                                   int hardQuota, int platformCompletionsMin,
                                                   int probationSeats, Set<String> excludedAccountIds) {
        return drawPicks(DRAW_MIN_TIER, organizationId, size, taskPlatform, hardQuota,
                platformCompletionsMin, probationSeats, excludedAccountIds);
    }

    private Mono<List<PanelPick>> drawPicks(int minTier, String organizationId, int size, String taskPlatform,
                                            int hardQuota, int platformCompletionsMin, int probationSeats,
                                            Set<String> excludedAccountIds) {
        if (size <= 0 || size > 200) {
            return Mono.error(new IllegalArgumentException("panel size 必须在 1-200 之间"));
        }
        int quota = hardQuota > 0 ? hardQuota : 0;
        int probationCap = probationSeats > 0 ? probationSeats : Integer.MAX_VALUE;
        Set<String> excluded = excludedAccountIds == null ? Set.of() : Set.copyOf(excludedAccountIds);
        // SQL 候选流下限固定为见习地板（卡 E）；精确资格由 revalidate 按谓词过滤。
        // minTier 参数保留兼容旧调用（其 Lv5-only 语义已被 V14 资格谓词取代）。
        return judges.streamEligibleCandidates(DRAW_MIN_TIER, organizationId)
                .filter(judge -> !excluded.contains(judge.accountId()))
                .flatMapSequential(judge -> revalidate(judge, organizationId), REVALIDATION_CONCURRENCY, 1)
                .take(MAX_CANDIDATES_VALIDATED)
                .collectList()
                .flatMap(candidates -> composePanel(candidates, size, taskPlatform, quota,
                        platformCompletionsMin, probationCap));
    }

    /**
     * 分池合成面板：复验阶段不做平台判定（远端完成数在 compose 时逐人补查），熟手优先、见习让位。
     * 选法：
     * <ol>
     *   <li>候选按「熟手优先 + full 优先」排序（熟手=该平台完成 ≥min；排序稳定，random() 已在 SQL 打乱）；</li>
     *   <li>先取满 hardQuota 熟手席（不足降级取可得数）；</li>
     *   <li>再按序补通用席，见习超 cap 时跳过留给后备轮；</li>
     *   <li>若跳过导致凑不齐，后备轮放宽见习上限补足（池尽容忍超编，快照记 probationCount）。</li>
     * </ol>
     */
    private Mono<List<PanelPick>> composePanel(List<Judge> candidates, int size, String taskPlatform,
                                               int hardQuota, int platformCompletionsMin, int probationCap) {
        if (candidates.size() < size) {
            return Mono.error(new TrustException(503, "无足够的合格审判官"));
        }
        if (taskPlatform == null || taskPlatform.isBlank() || hardQuota <= 0) {
            return Mono.just(orderFullFirst(candidates, size).stream()
                    .map(j -> new PanelPick(j, false)).toList());
        }
        return FluxCompletions.fetchAll(reputationClient, candidates)
                .flatMap(completions -> {
                    List<Judge> skilled = new ArrayList<>();
                    List<Judge> general = new ArrayList<>();
                    for (Judge judge : candidates) {
                        boolean matched = completions.getOrDefault(judge.accountId(),
                                MarketplaceReputationClient.PlatformCompletions.EMPTY)
                                .completionsOf(taskPlatform) >= platformCompletionsMin;
                        (matched ? skilled : general).add(judge);
                    }
                    skilled.sort(FULL_FIRST);
                    general.sort(FULL_FIRST);
                    List<PanelPick> picked = new ArrayList<>();
                    Set<String> taken = new HashSet<>();
                    int skilledSeats = Math.min(hardQuota, skilled.size());
                    for (Judge judge : skilled.subList(0, skilledSeats)) {
                        picked.add(new PanelPick(judge, true));
                        taken.add(judge.accountId());
                    }
                    List<Judge> fillPool = new ArrayList<>(general);
                    skilled.subList(skilledSeats, skilled.size()).forEach(fillPool::add); // 未入熟手席的熟手仍可补通用席
                    fillPool.sort(FULL_FIRST);
                    appendWithProbationCap(picked, taken, fillPool, size, probationCap, false);
                    if (picked.size() < size) {
                        List<Judge> overflow = new ArrayList<>(fillPool);
                        overflow.removeIf(j -> taken.contains(j.accountId()));
                        appendWithProbationCap(picked, taken, overflow, size, Integer.MAX_VALUE, true);
                    }
                    if (picked.size() < size) {
                        return Mono.error(new TrustException(503, "无足够的合格审判官"));
                    }
                    return Mono.just(List.copyOf(picked));
                });
    }

    /** 按序补席，见习超 cap 跳过（留后备轮）。 */
    private static void appendWithProbationCap(List<PanelPick> picked, Set<String> taken, List<Judge> pool,
                                               int size, int probationCap, boolean allowProbationOverflow) {
        int probation = (int) picked.stream().map(PanelPick::judge).filter(Judge::isProbation).count();
        for (Judge judge : pool) {
            if (picked.size() >= size) {
                return;
            }
            if (taken.contains(judge.accountId())) {
                continue;
            }
            if (judge.isProbation() && !allowProbationOverflow && probation >= probationCap) {
                continue;
            }
            picked.add(new PanelPick(judge, false));
            taken.add(judge.accountId());
            if (judge.isProbation()) {
                probation++;
            }
        }
    }

    /** 无垂类配额路径：full 优先、见习殿后（保持「先满 full 再见习」的稳态）。 */
    private static List<Judge> orderFullFirst(List<Judge> candidates, int size) {
        List<Judge> ordered = new ArrayList<>(candidates);
        ordered.sort(FULL_FIRST);
        return ordered.subList(0, size);
    }

    private static final java.util.Comparator<Judge> FULL_FIRST = java.util.Comparator
            .comparing((Judge j) -> j.isProbation() ? 1 : 0);

    /** 逐候选补查平台完成数（并发受限、fail-closed）。内部小工具避免在流上散布并发参数。 */
    private static final class FluxCompletions {
        private static Mono<java.util.Map<String, MarketplaceReputationClient.PlatformCompletions>> fetchAll(
                MarketplaceReputationClient client, List<Judge> candidates) {
            java.util.Map<String, MarketplaceReputationClient.PlatformCompletions> map = new java.util.concurrent.ConcurrentHashMap<>();
            return reactor.core.publisher.Flux.fromIterable(candidates)
                    .flatMapSequential(judge -> client.getPlatformCompletions(judge.accountId())
                                    .doOnNext(c -> map.put(judge.accountId(), c))
                                    .onErrorMap(error -> new TrustException(503, "声誉服务暂时不可用")),
                            REVALIDATION_CONCURRENCY, 1)
                    .then(Mono.just(map));
        }
    }

    /** Write-boundary revalidation. Call immediately before entering the local assignment transaction. */
    public Mono<Void> validateNoOrganizationConflicts(List<String> accountIds, String organizationId) {
        if (accountIds == null || accountIds.isEmpty()) {
            return Mono.empty();
        }
        return reactor.core.publisher.Flux.fromIterable(accountIds.stream().distinct().toList())
                .flatMapSequential(accountId -> authoritativeOrganizationIds(accountId)
                                .filter(organizationIds -> organizationIds.contains(organizationId))
                                .map(ignored -> accountId),
                        REVALIDATION_CONCURRENCY, 1)
                .next()
                .flatMap(conflictingAccount -> Mono.<Void>error(
                        new TrustException(503, "审判官组织归属已变化，请重试抽签")))
                .then();
    }

    private Mono<Judge> revalidate(Judge judge, String organizationId) {
        if (!judge.active() || !judge.opsAdmitted() || judge.suspendedNow()) {
            return Mono.empty();
        }
        // V14 资格谓词（卡 E）：Lv5 直入；Lv4 须考试及格（见习通道）；再低一票否决。
        if (judge.eligibilityTier() < DRAW_MIN_TIER
                || (judge.eligibilityTier() < 5 && judge.examPassedAt() == null)) {
            return Mono.empty();
        }
        return reputationClient.getLevel(judge.accountId())
                .onErrorMap(error -> new TrustException(503, "声誉服务暂时不可用"))
                // V14 资格谓词的远端半边：Lv5 直入；Lv4 须已有本地考试及格记录（见习通道）。
                .filter(level -> level.levelNumber() >= 5
                        || (level.levelNumber() >= DRAW_MIN_TIER && judge.examPassedAt() != null))
                .flatMap(ignored -> authoritativeOrganizationIds(judge.accountId()))
                .filter(organizationIds -> !organizationIds.contains(organizationId))
                .map(ignored -> judge);
    }

    private Mono<Set<String>> authoritativeOrganizationIds(String accountId) {
        return identityMemberships.organizationIds(accountId)
                .onErrorMap(error -> error instanceof TrustException
                        ? error
                        : new TrustException(503, "身份服务暂时不可用"));
    }
}
