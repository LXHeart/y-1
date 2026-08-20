package com.grassland.financial;

import java.time.Clock;
import java.time.Instant;
import java.math.RoundingMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * credits↔cents 换算政策的共享字段形状与状态机（2026-08-20 自 finance/intelligence 双份收敛）。
 *
 * <p>
 * 两服务绑定同一 {@code credits.cents-policy} 前缀（compose 强制同值注入），政策漂移曾靠 环境对齐 +
 * 各自手写校验兜底——字段集与完整性规则现在单源在本 record。异常语义刻意留在 服务侧（finance 的 503/409/400 与
 * intelligence 的 503 映射各自维护用户体验），本库只提供 中立的 {@link Status}
 * 与换算数学（{@link CreditsCentsPolicySnapshot#creditsFor}）。
 */
@ConfigurationProperties(prefix = "credits.cents-policy")
public record CreditsCentsPolicyProperties(String version, Instant effectiveAt, RoundingMode rounding,
		Long centsNumerator, Long creditsDenominator, Long maxCentsPerOperation) {

	/** 政策可执行性的中立判定；服务侧各自映射为领域异常。 */
	public enum Status {
		/** 全字段未配置（本地/未启用结算）。 */
		UNSET,
		/** 部分配置——配错，必须 fail-fast。 */
		INCOMPLETE,
		/** 完整但生效时刻在未来。 */
		NOT_YET_EFFECTIVE,
		/** 完整且已生效。 */
		ACTIVE,
	}

	public Status status() {
		return status(Clock.systemUTC());
	}

	public Status status(Clock clock) {
		boolean any = version != null && !version.isBlank() || effectiveAt != null || rounding != null
				|| centsNumerator != null || creditsDenominator != null || maxCentsPerOperation != null;
		if (!any) {
			return Status.UNSET;
		}
		if (!fullyConfigured()) {
			return Status.INCOMPLETE;
		}
		if (effectiveAt.isAfter(clock.instant())) {
			return Status.NOT_YET_EFFECTIVE;
		}
		return Status.ACTIVE;
	}

	public boolean fullyConfigured() {
		return version != null && !version.isBlank() && effectiveAt != null && rounding != null
				&& centsNumerator != null && centsNumerator > 0 && creditsDenominator != null && creditsDenominator > 0
				&& maxCentsPerOperation != null && maxCentsPerOperation > 0;
	}

	/** 已生效政策的不可变快照（换算数学见 {@link CreditsCentsPolicySnapshot}）。 */
	public CreditsCentsPolicySnapshot snapshot() {
		return new CreditsCentsPolicySnapshot(version, rounding, centsNumerator, creditsDenominator,
				maxCentsPerOperation);
	}
}
