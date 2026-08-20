package com.grassland.finance.credits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.finance.security.FinanceException;
import com.grassland.financial.CreditsCentsPolicyProperties;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * 政策状态机 → Finance 异常映射层的守卫测试（字段形状/数学单源在 platform-financial， 2026-08-20
 * 下沉；文案与状态码逐字保留原语义）。
 */
class CreditsCentsPolicyPropertiesTest {

	private static final Clock NOW = Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC);

	@Test
	void convertsCentsUsingConfiguredRounding() {
		var halfUp = CreditsPolicyGuards.requireActive(policy(RoundingMode.HALF_UP, 100, 1, 10_000), "v1", NOW);
		var down = CreditsPolicyGuards.requireActive(policy(RoundingMode.DOWN, 100, 1, 10_000), "v1", NOW);

		assertThat(CreditsPolicyGuards.creditsFor(halfUp, 149)).isEqualTo(1);
		assertThat(CreditsPolicyGuards.creditsFor(halfUp, 150)).isEqualTo(2);
		assertThat(CreditsPolicyGuards.creditsFor(down, 199)).isEqualTo(1);
	}

	@Test
	void supportsNonUnitConversionRatio() {
		var snapshot = CreditsPolicyGuards.requireActive(policy(RoundingMode.HALF_UP, 25, 10, 10_000), "v1", NOW);

		assertThat(CreditsPolicyGuards.creditsFor(snapshot, 10)).isEqualTo(4);
	}

	@Test
	void rejectsWrongVersionFuturePolicyAndOperationAboveCap() {
		var active = policy(RoundingMode.HALF_UP, 100, 1, 500);
		assertThatThrownBy(() -> CreditsPolicyGuards.requireActive(active, "v2", NOW))
				.isInstanceOfSatisfying(FinanceException.class, error -> assertThat(error.status()).isEqualTo(409));

		var future = new CreditsCentsPolicyProperties("v1", Instant.parse("2027-01-01T00:00:00Z"), RoundingMode.HALF_UP,
				100L, 1L, 500L);
		assertThatThrownBy(() -> CreditsPolicyGuards.requireActive(future, "v1", NOW))
				.isInstanceOfSatisfying(FinanceException.class, error -> assertThat(error.status()).isEqualTo(503));

		var snapshot = CreditsPolicyGuards.requireActive(active, "v1", NOW);
		assertThatThrownBy(() -> CreditsPolicyGuards.creditsFor(snapshot, 501))
				.isInstanceOfSatisfying(FinanceException.class, error -> assertThat(error.status()).isEqualTo(400));
	}

	private static CreditsCentsPolicyProperties policy(RoundingMode rounding, long centsNumerator,
			long creditsDenominator, long maxCents) {
		return new CreditsCentsPolicyProperties("v1", Instant.parse("2026-01-01T00:00:00Z"), rounding, centsNumerator,
				creditsDenominator, maxCents);
	}
}
