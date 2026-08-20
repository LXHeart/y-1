package com.grassland.financial;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 政策换算快照与数学（原 finance 内嵌 Snapshot 单源化）。纯函数：非法区间抛中立
 * {@link IllegalArgumentException}，换算溢出抛 {@link ArithmeticException}——
 * 服务侧各自映射为领域异常（finance → 400）。
 */
public record CreditsCentsPolicySnapshot(String version, RoundingMode rounding, long centsNumerator,
		long creditsDenominator, long maxCentsPerOperation) {

	private static final long MAX_CREDITS_PER_OPERATION = 1_000_000L;

	public int creditsFor(long cents) {
		if (cents < 0 || cents > maxCentsPerOperation) {
			throw new IllegalArgumentException("cents out of policy range");
		}
		try {
			long credits = BigDecimal.valueOf(cents).multiply(BigDecimal.valueOf(creditsDenominator))
					.divide(BigDecimal.valueOf(centsNumerator), 0, rounding).longValueExact();
			if (credits < 0 || credits > MAX_CREDITS_PER_OPERATION) {
				throw new ArithmeticException("converted credits out of range");
			}
			return Math.toIntExact(credits);
		} catch (ArithmeticException error) {
			throw new ArithmeticException("credits conversion out of supported range");
		}
	}
}
