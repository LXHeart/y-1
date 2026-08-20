package com.grassland.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 状态机四态与换算数学的边界（原两服务手写校验的单测盲区随下沉补齐）。 */
class CreditsCentsPolicyPropertiesTest {

	private static final Clock NOW = Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC);

	private static CreditsCentsPolicyProperties full(Instant effectiveAt) {
		return new CreditsCentsPolicyProperties("v1", effectiveAt, RoundingMode.HALF_UP, 3L, 100L, 2_000_000L);
	}

	@Test
	void statusIsUnsetWhenNoFieldPresent() {
		var props = new CreditsCentsPolicyProperties(null, null, null, null, null, null);
		assertThat(props.status(NOW)).isEqualTo(CreditsCentsPolicyProperties.Status.UNSET);
	}

	@Test
	void statusIsIncompleteWhenPartiallyConfigured() {
		var props = new CreditsCentsPolicyProperties("v1", null, null, null, null, null);
		assertThat(props.status(NOW)).isEqualTo(CreditsCentsPolicyProperties.Status.INCOMPLETE);
		var nonPositive = new CreditsCentsPolicyProperties("v1", Instant.now(), RoundingMode.HALF_UP, 0L, 100L, 1L);
		assertThat(nonPositive.status(NOW)).isEqualTo(CreditsCentsPolicyProperties.Status.INCOMPLETE);
	}

	@Test
	void statusIsNotYetEffectiveUntilEffectiveAt() {
		assertThat(full(Instant.parse("2026-08-20T00:00:01Z")).status(NOW))
				.isEqualTo(CreditsCentsPolicyProperties.Status.NOT_YET_EFFECTIVE);
		assertThat(full(Instant.parse("2026-08-19T23:59:59Z")).status(NOW))
				.isEqualTo(CreditsCentsPolicyProperties.Status.ACTIVE);
	}

	@Test
	void snapshotMatchesMathOfLegacyFinanceSnapshot() {
		// 3 分 = 100 credits：HALF_UP 下 1 分 → 33，2 分 → 67
		var snapshot = full(Instant.EPOCH).snapshot();
		assertThat(snapshot.creditsFor(1)).isEqualTo(33);
		assertThat(snapshot.creditsFor(2)).isEqualTo(67);
		assertThat(snapshot.creditsFor(3)).isEqualTo(100);
	}

	@Test
	void creditsForRejectsOutOfRangeCentsAndOverflow() {
		var snapshot = full(Instant.EPOCH).snapshot();
		assertThatThrownBy(() -> snapshot.creditsFor(-1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> snapshot.creditsFor(2_000_001)).isInstanceOf(IllegalArgumentException.class);
		// 分母极大放大后溢出 → 中立 ArithmeticException（服务侧映射 400）
		var overflowing = new CreditsCentsPolicySnapshot("v1", RoundingMode.HALF_UP, 1L, Long.MAX_VALUE / 2,
				1_000_000L);
		assertThatThrownBy(() -> overflowing.creditsFor(1_000_000)).isInstanceOf(ArithmeticException.class);
	}
}
