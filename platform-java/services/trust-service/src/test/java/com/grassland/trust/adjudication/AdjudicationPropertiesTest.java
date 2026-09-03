package com.grassland.trust.adjudication;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 窗口配置换算（秒级覆盖 vs 小时值）。
 *
 * <p>
 * 背景：窗口原本只有小时粒度，最小非零值 1 小时 —— dev/e2e 无法验证 「窗口到期→自动
 * tally→decided→上诉窗口→终局」这条时间驱动主链路。 加秒级覆盖后本测试锁死优先级，防止生产误用 dev 值或反之。
 */
class AdjudicationPropertiesTest {

	/** 生产形态：不设秒级覆盖 → 用小时换算。 */
	@Test
	void fallsBackToHoursWhenNoSecondsOverride() {
		var props = new AdjudicationProperties(7, 24, 2, 48, 1, 48, 1, 168, 168, 60, 0, 0, 0, 0, 0, 0, 48, 0);

		assertThat(props.voteWindowSecondsEffective()).isEqualTo(24 * 3600L);
		assertThat(props.appealWindowSecondsEffective()).isEqualTo(48 * 3600L);
		assertThat(props.adjudicationWindowSecondsEffective()).isEqualTo(48 * 3600L);
		assertThat(props.disputeCooldownSecondsEffective()).isEqualTo(168 * 3600L);
	}

	/** dev/e2e 形态：秒级覆盖优先于小时值。 */
	@Test
	void secondsOverrideTakesPrecedence() {
		var props = new AdjudicationProperties(7, 24, 2, 48, 1, 48, 1, 168, 168, 60, 60, 90, 120, 150, 0, 0, 48, 0);

		assertThat(props.voteWindowSecondsEffective()).isEqualTo(60L);
		assertThat(props.appealWindowSecondsEffective()).isEqualTo(90L);
		assertThat(props.adjudicationWindowSecondsEffective()).isEqualTo(120L);
		assertThat(props.disputeCooldownSecondsEffective()).isEqualTo(150L);
	}

	/** 两窗口独立覆盖（只设一个时，另一个仍用小时值）。 */
	@Test
	void overridesAreIndependent() {
		var props = new AdjudicationProperties(7, 24, 2, 48, 1, 48, 1, 168, 168, 60, 30, 0, 0, 0, 0, 0, 48, 0);

		assertThat(props.voteWindowSecondsEffective()).isEqualTo(30L);
		assertThat(props.appealWindowSecondsEffective()).isEqualTo(48 * 3600L);
		assertThat(props.adjudicationWindowSecondsEffective()).isEqualTo(48 * 3600L);
		assertThat(props.disputeCooldownSecondsEffective()).isEqualTo(168 * 3600L);
	}

	/** 负值视作未覆盖（防止误配成负数导致 Workflow.sleep 异常）。 */
	@Test
	void negativeOverrideIsTreatedAsUnset() {
		var props = new AdjudicationProperties(7, 24, 2, 48, 1, 48, 1, 168, 168, 60, -5, -1, -1, -1, 0, 0, 48, 0);

		assertThat(props.voteWindowSecondsEffective()).isEqualTo(24 * 3600L);
		assertThat(props.appealWindowSecondsEffective()).isEqualTo(48 * 3600L);
		assertThat(props.adjudicationWindowSecondsEffective()).isEqualTo(48 * 3600L);
		assertThat(props.disputeCooldownSecondsEffective()).isEqualTo(168 * 3600L);
	}

	/** 小时值非法（0/负）时的既有兜底不受影响。 */
	@Test
	void invalidHoursStillFallBackToDefaults() {
		var props = new AdjudicationProperties(0, 0, 0, 0, 0, 0, 1, -1, 0, 0, 0, 0, 0, 0, 0, 0, 48, 0);

		assertThat(props.panelSize()).isEqualTo(7);
		assertThat(props.judgeEligibilityTier()).isEqualTo(5);
		assertThat(props.voteWindowSecondsEffective()).isEqualTo(24 * 3600L);
		assertThat(props.appealWindowSecondsEffective()).isEqualTo(48 * 3600L);
		assertThat(props.adjudicationWindowSecondsEffective()).isEqualTo(48 * 3600L);
		// disputeCooldownHours 负值默认为 168
		assertThat(props.disputeCooldownSecondsEffective()).isEqualTo(168 * 3600L);
		// 验证 0 保留为禁用（测试环境用）
		var propsWithDefault = new AdjudicationProperties(0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 48, 0);
		assertThat(propsWithDefault.disputeCooldownSecondsEffective()).isEqualTo(0);
	}
}
