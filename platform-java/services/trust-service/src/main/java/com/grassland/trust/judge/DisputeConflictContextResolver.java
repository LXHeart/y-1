package com.grassland.trust.judge;

import com.grassland.trust.dispute.DisputeCase;
import com.grassland.trust.dispute.DisputeCaseRepository;
import com.grassland.trust.dispute.MarketplaceEngagementAuthorizationClient;
import com.grassland.trust.security.TrustException;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 案件冲突上下文解析器（审查修复 02 / C02-A）。
 *
 * <p>
 * 读取案件权威双方账号构造 {@link DisputeConflictContext}，供所有 draw/assign/repair/retrial
 * 入口共用（原告 openedByAccountId 与被告 respondentAccountId 都不得担任本案审判官）。
 *
 * <p>
 * 存量 respondent_account_id 为空的行：
 * <ul>
 * <li>推荐官开案 → 被诉方是商家组织（org 维度），无单一账号可排除；组织级冲突由既有
 * 同组织/显式冲突规则覆盖，不视为「没有被告冲突」放行。</li>
 * <li>商家开案 → 被诉方=该履约的推荐官，按既有履约授权接口
 * （{@link MarketplaceEngagementAuthorizationClient}）补解析并回填落库；
 * 解析失败（非当事方/履约已终结/上游不可用）抛可解释的 503，案件保持可重试的待处理状态， 绝不用商家组织中的任意账号猜被告。</li>
 * </ul>
 */
@Component
public class DisputeConflictContextResolver {

	private final DisputeCaseRepository disputes;
	private final MarketplaceEngagementAuthorizationClient authorizer;

	public DisputeConflictContextResolver(DisputeCaseRepository disputes,
			MarketplaceEngagementAuthorizationClient authorizer) {
		this.disputes = disputes;
		this.authorizer = authorizer;
	}

	public Mono<DisputeConflictContext> resolve(String disputeId) {
		return disputes.findById(disputeId).switchIfEmpty(Mono.error(new TrustException(404, "争议不存在")))
				.flatMap(this::resolveParties);
	}

	private Mono<DisputeConflictContext> resolveParties(DisputeCase dispute) {
		Set<String> parties = new HashSet<>();
		if (dispute.openedByAccountId() != null) {
			parties.add(dispute.openedByAccountId());
		}
		if (dispute.respondentAccountId() != null) {
			parties.add(dispute.respondentAccountId());
			return Mono.just(new DisputeConflictContext(dispute.id(), dispute.organizationId(), parties));
		}
		if (!"merchant".equals(dispute.openedByRole())) {
			// 推荐官开案：被诉方为商家组织，冲突按组织级排除（见类 javadoc）。
			return Mono.just(new DisputeConflictContext(dispute.id(), dispute.organizationId(), parties));
		}
		// 商家开案的存量空被告：按既有履约授权接口补解析（商家本人是当事方，可携带授权）。
		return authorizer.authorize(dispute.engagementRef(), dispute.openedByAccountId(), "merchant")
				.onErrorMap(MarketplaceEngagementAuthorizationClient.AuthorizationException.class,
						error -> new TrustException(503, "被诉方账号解析失败（授权服务暂不可用），请稍后重试"))
				.switchIfEmpty(Mono.error(new TrustException(503, "被诉方账号未能解析（履约授权不可用或已终结），暂不能组建审判面板，请稍后重试")))
				.flatMap(auth -> disputes.backfillRespondentAccount(dispute.id(), auth.recommenderAccountId())
						.thenReturn(new DisputeConflictContext(dispute.id(), dispute.organizationId(),
								withParty(parties, auth.recommenderAccountId()))));
	}

	private static Set<String> withParty(Set<String> parties, String accountId) {
		Set<String> merged = new HashSet<>(parties);
		if (accountId != null) {
			merged.add(accountId);
		}
		return merged;
	}
}
