package com.grassland.intelligence.ai.run;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * AI 用量统计（GL-P3-AI-001 Phase 4）。
 * <p>用于前端展示和账单对账。
 */
public record UsageStatistics(
    // 时间范围
    Instant startDate,
    Instant endDate,

    // 汇总用量
    long totalTokens,
    long totalImages,
    long totalVideoSeconds,

    // 汇总成本（分）
    long totalCents,
    long actualCents,  // 实际结算（可能因失败而退回）

    // 按能力分组
    CapabilityStats text,
    CapabilityStats imageGeneration,
    CapabilityStats videoGeneration,

    // 按状态分组
    long completedCount,
    long failedCount,
    long cancelledCount
) {
    /** 单个能力的统计。 */
    public record CapabilityStats(
        long runs,
        long tokens,
        long images,
        long videoSeconds,
        long cents
    ) {
        public static CapabilityStats empty() {
            return new CapabilityStats(0, 0, 0, 0, 0);
        }
    }

    /** 空统计。 */
    public static UsageStatistics empty(Instant start, Instant end) {
        return new UsageStatistics(
            start, end,
            0, 0, 0, 0, 0,
            CapabilityStats.empty(),
            CapabilityStats.empty(),
            CapabilityStats.empty(),
            0, 0, 0
        );
    }
}
