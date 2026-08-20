package com.grassland.finance.credits;

import com.grassland.financial.CreditsCentsPolicyProperties;
import com.grassland.financial.CreditsCentsPolicySnapshot;
import com.grassland.finance.security.FinanceException;
import java.time.Clock;

/**
 * 政策状态机 → Finance 领域异常的映射层（字段形状/状态机/换算数学单源在 platform-financial， 2026-08-20
 * 下沉；本类只保留 finance 的用户体验语义：503 未配置/未生效、409 版本冲突、400 换算越界）。
 */
public final class CreditsPolicyGuards {

	private CreditsPolicyGuards() {
	}

	public static CreditsCentsPolicySnapshot requireActive(CreditsCentsPolicyProperties policy,
			String expectedVersion) {
		return requireActive(policy, expectedVersion, Clock.systemUTC());
	}

	static CreditsCentsPolicySnapshot requireActive(CreditsCentsPolicyProperties policy, String expectedVersion,
			Clock clock) {
		switch (policy.status(clock)) {
			case UNSET :
			case INCOMPLETE :
				throw new FinanceException(503, "credits↔cents 换算政策未配置");
			case NOT_YET_EFFECTIVE :
				throw new FinanceException(503, "credits↔cents 换算政策尚未生效");
			case ACTIVE :
			default :
				break;
		}
		if (expectedVersion == null || !policy.version().equals(expectedVersion)) {
			throw new FinanceException(409, "credits↔cents 换算政策版本不一致");
		}
		return policy.snapshot();
	}

	public static int creditsFor(CreditsCentsPolicySnapshot snapshot, long cents) {
		try {
			return snapshot.creditsFor(cents);
		} catch (IllegalArgumentException error) {
			throw new FinanceException(400, "AI 成本超出单次 credits↔cents 政策范围");
		} catch (ArithmeticException error) {
			throw new FinanceException(400, "credits↔cents 换算结果超出支持范围");
		}
	}
}
