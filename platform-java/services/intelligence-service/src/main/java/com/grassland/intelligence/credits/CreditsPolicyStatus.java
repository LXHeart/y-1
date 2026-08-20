package com.grassland.intelligence.credits;

import com.grassland.financial.CreditsCentsPolicyProperties;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Optional;

/**
 * 政策状态机 → intelligence 领域语义的映射层（字段形状/状态机单源在 platform-financial， 2026-08-20
 * 下沉）。UNSET = 未启用结算（空 Optional，调用方据此跳过定价 run）； INCOMPLETE/NOT_YET_EFFECTIVE 保持原
 * 503 文案。
 */
public final class CreditsPolicyStatus {

	private CreditsPolicyStatus() {
	}

	public static Optional<String> activeVersion(CreditsCentsPolicyProperties policy) {
		switch (policy.status()) {
			case UNSET :
				return Optional.empty();
			case INCOMPLETE :
				throw new IntelligenceException(503, "credits↔cents 换算政策配置不完整");
			case NOT_YET_EFFECTIVE :
				throw new IntelligenceException(503, "credits↔cents 换算政策尚未生效");
			case ACTIVE :
			default :
				return Optional.of(policy.version());
		}
	}
}
