package com.grassland.trust.judge;

import com.grassland.trust.security.TrustException;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 抽签时实时复验 marketplace 的有效 Lv5 资格，避免只信报名时缓存的等级。 */
@Component
public class JudgeEligibilityService {

    private static final int REVALIDATION_CONCURRENCY = 4;
    private final JudgeRepository judges;
    private final MarketplaceReputationClient reputationClient;
    private final IdentityOrganizationMembershipClient identityMemberships;

    public JudgeEligibilityService(JudgeRepository judges, MarketplaceReputationClient reputationClient,
                                   IdentityOrganizationMembershipClient identityMemberships) {
        this.judges = judges;
        this.reputationClient = reputationClient;
        this.identityMemberships = identityMemberships;
    }

    /**
     * 流式读取全部本地候选并逐账号实时复验，找到完整面板后停止订阅。任何上游/解码错误使整个抽签失败；
     * 有效人数不足完整面板时也失败，不允许用残缺面板启动审判。
     */
    public Mono<List<Judge>> drawVerifiedPool(int minTier, String organizationId, int size) {
        return drawVerifiedPool(minTier, organizationId, size, Set.of());
    }

    /** 抽取新候选时先排除已经固化在当前面板中的账号，避免重复远端复验或补位不足。 */
    public Mono<List<Judge>> drawVerifiedPool(int minTier, String organizationId, int size,
                                              Set<String> excludedAccountIds) {
        if (size <= 0 || size > 200) {
            return Mono.error(new IllegalArgumentException("panel size 必须在 1-200 之间"));
        }
        Set<String> excluded = excludedAccountIds == null ? Set.of() : Set.copyOf(excludedAccountIds);
        return judges.streamEligibleCandidates(Math.max(5, minTier), organizationId)
                .filter(judge -> !excluded.contains(judge.accountId()))
                .flatMapSequential(judge -> revalidate(judge, organizationId), REVALIDATION_CONCURRENCY, 1)
                .take(size)
                .collectList()
                .flatMap(eligible -> eligible.size() < size
                        ? Mono.error(new TrustException(503, "无足够的合格审判官"))
                        : Mono.just(List.copyOf(eligible)));
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
        if (!judge.active() || !judge.opsAdmitted() || judge.eligibilityTier() < 5) {
            return Mono.empty();
        }
        return reputationClient.getLevel(judge.accountId())
                .onErrorMap(error -> new TrustException(503, "声誉服务暂时不可用"))
                .map(MarketplaceReputationClient.LevelResult::isEligibleLv5Judge)
                .filter(Boolean::booleanValue)
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
