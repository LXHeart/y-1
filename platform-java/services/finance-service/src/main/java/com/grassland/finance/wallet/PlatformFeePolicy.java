package com.grassland.finance.wallet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 平台佣金抽成口径（PRD 十「商业模式」）。
 *
 * <p><b>默认 0</b>——PRD 明确前期（冷启动）全免费，中期才「建议 5%-10%」。
 * 抽成率是产品决策，不该由实现替产品拍板，故做成配置项 {@code finance.split.platform-fee-bps}
 * （基点：500 = 5%），默认不抽。
 *
 * <p>取整方向：抽成向下取整（{@code floor}），差额归推荐官——宁可平台少收一分，不让用户莫名少到账一分。
 */
@Component
public class PlatformFeePolicy {

    private static final int MAX_BPS = 10_000;

    private final int feeBps;

    public PlatformFeePolicy(@Value("${finance.split.platform-fee-bps:0}") int feeBps) {
        if (feeBps < 0 || feeBps > MAX_BPS) {
            throw new IllegalArgumentException("finance.split.platform-fee-bps must be within [0, 10000], got " + feeBps);
        }
        this.feeBps = feeBps;
    }

    public int feeBps() {
        return feeBps;
    }

    /** 平台抽成（向下取整）。 */
    public long feeFor(long grossCents) {
        return grossCents * feeBps / MAX_BPS;
    }

    /** 推荐官实际到账 = 毛额 - 抽成。 */
    public long payoutFor(long grossCents) {
        return grossCents - feeFor(grossCents);
    }
}
