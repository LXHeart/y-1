package com.grassland.marketplace.reputation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 任务书 #98 D98-04：商家信用派生服务（读时计算，无硬门槛、无自动惩罚）。
 *
 * <p>三档标签（良好/正常/关注）+ 样本不足中性态；口径版本随响应下发。任务列表软排序用
 * {@link MerchantCredit#sortOrdinal()}：良好=0，正常/样本不足=1，关注=2（仅同分内生效）。
 */
@Component
public class MerchantCreditService {

	private final MerchantCreditRepository repository;
	private final MerchantCreditPolicy policy;

	public MerchantCreditService(MerchantCreditRepository repository, MerchantCreditPolicy policy) {
		this.repository = repository;
		this.policy = policy;
	}

	/** 指标：分子/分母/基点率（分母 0 时率记 0——无事实不构成失信）。 */
	public record Metric(String key, String label, long numerator, long denominator, int rateBps) {
	}

	public record MerchantCredit(String organizationId, String label, boolean insufficientSamples, long sampleCount,
			String policyVersion, Map<String, Metric> metrics, Map<String, Object> thresholds, Instant computedAt) {

		static final String LABEL_GOOD = "良好";
		static final String LABEL_NORMAL = "正常";
		static final String LABEL_WATCH = "关注";

		/** 软排序序（良好=0；样本不足与正常同级=1——无标签不惩罚；关注=2）。 */
		public int sortOrdinal() {
			if (insufficientSamples || LABEL_NORMAL.equals(label)) {
				return 1;
			}
			return LABEL_GOOD.equals(label) ? 0 : 2;
		}
	}

	public Mono<MerchantCredit> compute(String organizationId) {
		return repository.facts(organizationId).map(facts -> derive(organizationId, facts));
	}

	private MerchantCredit derive(String organizationId, MerchantCreditRepository.OrgCreditFacts facts) {
		int cancelRate = rateBps(facts.cancelledTasks(), facts.publishedTasks());
		int confirmTimeoutRate = rateBps(facts.autoConfirmations(), facts.confirmations());
		int benefitDefaultRate = rateBps(facts.benefitDefaults(), facts.benefits());
		int disputeLossRate = rateBps(facts.disputeLosses(), facts.resolvedDisputes());
		boolean insufficient = facts.cooperations() < policy.minSamples();
		String label = insufficient ? null
				: policy.watchLevel(cancelRate, confirmTimeoutRate, benefitDefaultRate, disputeLossRate)
						? MerchantCredit.LABEL_WATCH
						: policy.goodLevel(cancelRate, confirmTimeoutRate, benefitDefaultRate, disputeLossRate)
								? MerchantCredit.LABEL_GOOD
								: MerchantCredit.LABEL_NORMAL;
		Map<String, Metric> metrics = new LinkedHashMap<>();
		metrics.put("cancelRate", new Metric("cancelRate", "发布后取消率", facts.cancelledTasks(),
				facts.publishedTasks(), cancelRate));
		metrics.put("confirmTimeoutRate", new Metric("confirmTimeoutRate", "验收超时率", facts.autoConfirmations(),
				facts.confirmations(), confirmTimeoutRate));
		metrics.put("benefitDefaultRate", new Metric("benefitDefaultRate", "体验失约率", facts.benefitDefaults(),
				facts.benefits(), benefitDefaultRate));
		metrics.put("disputeLossRate", new Metric("disputeLossRate", "争议败诉率", facts.disputeLosses(),
				facts.resolvedDisputes(), disputeLossRate));
		return new MerchantCredit(organizationId, label, insufficient, facts.cooperations(),
				MerchantCreditPolicy.POLICY_VERSION, metrics, policy.thresholdsBody(), Instant.now());
	}

	private static int rateBps(long numerator, long denominator) {
		if (denominator <= 0) {
			return 0;
		}
		return (int) Math.min(Integer.MAX_VALUE, numerator * 10_000 / denominator);
	}

	/** 详情/列表内嵌摘要体（§6）。 */
	public Map<String, Object> summaryBody(MerchantCredit credit) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("label", credit.label());
		body.put("insufficientSamples", credit.insufficientSamples());
		body.put("sampleCount", credit.sampleCount());
		body.put("policyVersion", credit.policyVersion());
		return body;
	}

	/** 完整读端点体（GET /api/merchants/{orgId}/credit）。 */
	public Map<String, Object> fullBody(MerchantCredit credit) {
		Map<String, Object> body = summaryBody(credit);
		Map<String, Object> metrics = new LinkedHashMap<>();
		credit.metrics().values().forEach(metric -> metrics.put(metric.key(), Map.of("label", metric.label(),
				"numerator", metric.numerator(), "denominator", metric.denominator(), "rateBps", metric.rateBps())));
		body.put("metrics", metrics);
		body.put("thresholds", credit.thresholds());
		body.put("computedAt", credit.computedAt().toString());
		return body;
	}
}
