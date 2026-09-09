package com.grassland.marketplace.reputation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 任务书 #98 D98-04：商家信用 v1 口径（读时派生、无硬门槛）。
 *
 * <ul>
 * <li>四指标：发布后取消率 / 验收超时率（auto_confirmed_at 事实）/ 体验失约率 （experience_benefit
 * merchant_defaulted）/ 争议败诉率（售后裁定 refund）；</li>
 * <li>三档标签（良好/正常/关注）：全指标 ≤ good 阈值 = 良好；任一指标 ≥ watch 阈值 = 关注； 其间 = 正常；合作数 &lt;
 * minSamples = 样本不足（不展示标签、不参与分档）；</li>
 * <li>阈值走配置（治理台经部署配置调整）；口径版本常量随响应下发（快照随行走 D96-01 惯例）；
 * 改阈值不重算历史展示、不产生任何自动惩罚动作。</li>
 * </ul>
 */
@Component
public class MerchantCreditPolicy {

	public static final String POLICY_VERSION = "merchant_credit_v1";

	private final int minSamples;
	private final int cancelGoodBps;
	private final int cancelWatchBps;
	private final int confirmTimeoutGoodBps;
	private final int confirmTimeoutWatchBps;
	private final int benefitDefaultGoodBps;
	private final int benefitDefaultWatchBps;
	private final int disputeLossGoodBps;
	private final int disputeLossWatchBps;

	public MerchantCreditPolicy(@Value("${marketplace.merchant-credit.min-samples:10}") int minSamples,
			@Value("${marketplace.merchant-credit.cancel-rate-good-bps:500}") int cancelGoodBps,
			@Value("${marketplace.merchant-credit.cancel-rate-watch-bps:2000}") int cancelWatchBps,
			@Value("${marketplace.merchant-credit.confirm-timeout-rate-good-bps:1000}") int confirmTimeoutGoodBps,
			@Value("${marketplace.merchant-credit.confirm-timeout-rate-watch-bps:3000}") int confirmTimeoutWatchBps,
			@Value("${marketplace.merchant-credit.benefit-default-rate-good-bps:500}") int benefitDefaultGoodBps,
			@Value("${marketplace.merchant-credit.benefit-default-rate-watch-bps:2000}") int benefitDefaultWatchBps,
			@Value("${marketplace.merchant-credit.dispute-loss-rate-good-bps:1000}") int disputeLossGoodBps,
			@Value("${marketplace.merchant-credit.dispute-loss-rate-watch-bps:3000}") int disputeLossWatchBps) {
		this.minSamples = Math.max(minSamples, 0);
		this.cancelGoodBps = cancelGoodBps;
		this.cancelWatchBps = cancelWatchBps;
		this.confirmTimeoutGoodBps = confirmTimeoutGoodBps;
		this.confirmTimeoutWatchBps = confirmTimeoutWatchBps;
		this.benefitDefaultGoodBps = benefitDefaultGoodBps;
		this.benefitDefaultWatchBps = benefitDefaultWatchBps;
		this.disputeLossGoodBps = disputeLossGoodBps;
		this.disputeLossWatchBps = disputeLossWatchBps;
	}

	public int minSamples() {
		return minSamples;
	}

	/** 任一指标达 watch 阈值 → 关注。 */
	boolean watchLevel(int cancelBps, int confirmTimeoutBps, int benefitDefaultBps, int disputeLossBps) {
		return cancelBps >= cancelWatchBps || confirmTimeoutBps >= confirmTimeoutWatchBps
				|| benefitDefaultBps >= benefitDefaultWatchBps || disputeLossBps >= disputeLossWatchBps;
	}

	/** 全指标不超过 good 阈值 → 良好。 */
	boolean goodLevel(int cancelBps, int confirmTimeoutBps, int benefitDefaultBps, int disputeLossBps) {
		return cancelBps <= cancelGoodBps && confirmTimeoutBps <= confirmTimeoutGoodBps
				&& benefitDefaultBps <= benefitDefaultGoodBps && disputeLossBps <= disputeLossGoodBps;
	}

	public java.util.Map<String, Object> thresholdsBody() {
		java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
		body.put("cancelRate", java.util.Map.of("goodBps", cancelGoodBps, "watchBps", cancelWatchBps));
		body.put("confirmTimeoutRate",
				java.util.Map.of("goodBps", confirmTimeoutGoodBps, "watchBps", confirmTimeoutWatchBps));
		body.put("benefitDefaultRate",
				java.util.Map.of("goodBps", benefitDefaultGoodBps, "watchBps", benefitDefaultWatchBps));
		body.put("disputeLossRate", java.util.Map.of("goodBps", disputeLossGoodBps, "watchBps", disputeLossWatchBps));
		return body;
	}
}
