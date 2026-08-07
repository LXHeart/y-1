package com.grassland.finance.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 平台抽成口径。默认 0 是**产品决策**（PRD 十：前期冷启动全免费，中期才「建议 5%-10%」），
 * 用测试锁住，避免哪天被顺手改成一个拍脑袋的数字。
 */
class PlatformFeePolicyTest {

    @Test
    void defaultTakesNothing() {
        PlatformFeePolicy policy = new PlatformFeePolicy(0);

        assertThat(policy.feeFor(50_000)).isZero();
        assertThat(policy.payoutFor(50_000)).isEqualTo(50_000);
    }

    @Test
    void fivePercentSplitsGrossIntoFeeAndPayout() {
        PlatformFeePolicy policy = new PlatformFeePolicy(500);   // 5%

        assertThat(policy.feeFor(50_000)).isEqualTo(2_500);
        assertThat(policy.payoutFor(50_000)).isEqualTo(47_500);
        // 不丢钱：抽成 + 到账 == 毛额
        assertThat(policy.feeFor(50_000) + policy.payoutFor(50_000)).isEqualTo(50_000);
    }

    /** 取整方向：抽成向下取整，零头归推荐官——宁可平台少收一分。 */
    @Test
    void roundsFeeDownSoUserNeverLosesACent() {
        PlatformFeePolicy policy = new PlatformFeePolicy(500);

        assertThat(policy.feeFor(19)).isZero();          // 0.95 分 → 0
        assertThat(policy.payoutFor(19)).isEqualTo(19);
        assertThat(policy.feeFor(1)).isZero();
        assertThat(policy.payoutFor(1)).isEqualTo(1);
    }

    @Test
    void calculatesLargeAmountsWithoutIntermediateOverflow() {
        PlatformFeePolicy policy = new PlatformFeePolicy(10_000);

        assertThat(policy.feeFor(Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
        assertThat(policy.payoutFor(Long.MAX_VALUE)).isZero();
    }

    @Test
    void rejectsOutOfRangeConfiguration() {
        assertThatThrownBy(() -> new PlatformFeePolicy(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlatformFeePolicy(10_001)).isInstanceOf(IllegalArgumentException.class);
    }
}
