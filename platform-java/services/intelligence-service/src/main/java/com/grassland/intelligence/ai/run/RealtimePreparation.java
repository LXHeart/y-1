package com.grassland.intelligence.ai.run;

import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.credits.CreditFeature;
import java.util.Objects;
import java.util.UUID;

/**
 * 实时管道执行准备命令（任务书 #105D C105D-01 / 共享契约 K08）。
 *
 * <p>
 * {@code operationId} 是<b>稳定经济键</b>：同一 dh_invocation 的每次重试都携带同一个 id，绝不重新生成
 * （否则崩溃重试会造出第二笔预算预留）。{@code provider} 与 {@code priceTableVersion} 由调用方在
 * invocation 登记时冻结传入——本入口不再现场路由、不静默换主备（allowFallback 语义不适用）。个人 BYOK 的 feature
 * 由调用方按 K08.1 置 null（平台不代扣）。
 *
 * <p>
 * 数字人首期固定个人：{@code organizationId} 恒为 null（K05：dh 表无组织归属）。
 */
public record RealtimePreparation(UUID operationId, String accountId, String organizationId, String capability,
		CreditFeature feature, ProviderResolution provider, String priceTableVersion, int estimatedInputTokens,
		int estimatedOutputTokens, int estimatedSeconds) {

	public RealtimePreparation {
		Objects.requireNonNull(operationId, "operationId 必填（稳定经济键）");
		Objects.requireNonNull(accountId, "accountId 必填");
		Objects.requireNonNull(capability, "capability 必填");
		Objects.requireNonNull(provider, "冻结 provider 必填");
		Objects.requireNonNull(priceTableVersion, "冻结 priceTableVersion 必填");
		if (organizationId != null) {
			throw new IllegalArgumentException("数字人实时管道首期固定个人（organizationId=null）");
		}
		if (estimatedInputTokens < 0 || estimatedOutputTokens < 0 || estimatedSeconds < 0) {
			throw new IllegalArgumentException("预估用量不能为负数");
		}
	}
}
