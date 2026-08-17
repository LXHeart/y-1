package com.grassland.intelligence.ai.run;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * AI 模型预算配置（GL-P3-AI-001 Phase 3）。
 * <p>组织级的预算限制，防止超支。
 */
public record AiModelBudget(
    UUID id,
    String organizationId,
    String capability,            // text/image_generation 等
    String provider,              // platform 或特定 provider

    // 预算限制（任一达限即停止）
    Integer maxTokensPerRun,
    Long maxTokensDaily,
    Long maxTokensMonthly,
    Integer maxCentsPerRun,
    Long maxCentsDaily,
    Long maxCentsMonthly,

    // 统计（用于计算已用量）
    Long currentDailyTokens,
    Long currentDailyCents,
    Long currentMonthlyTokens,
    Long currentMonthlyCents,
    LocalDate lastResetDate,

    long version,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt
) {
    /** 创建新预算配置。 */
    public static AiModelBudget forCreate(
        String organizationId,
        String capability,
        String provider,
        Integer maxTokensPerRun,
        Long maxTokensDaily,
        Long maxTokensMonthly,
        Integer maxCentsPerRun,
        Long maxCentsDaily,
        Long maxCentsMonthly
    ) {
        return new AiModelBudget(
            null,  // id 由数据库生成
            organizationId,
            capability,
            provider,
            maxTokensPerRun,
            maxTokensDaily,
            maxTokensMonthly,
            maxCentsPerRun,
            maxCentsDaily,
            maxCentsMonthly,
            0L,   // currentDailyTokens
            0L,   // currentDailyCents
            0L,   // currentMonthlyTokens
            0L,   // currentMonthlyCents
            LocalDate.now(),
            1L,
            true,
            null,  // createdAt 由数据库默认
            null   // updatedAt 由数据库默认
        );
    }

    /** 检查是否超过单次 Run 预算。 */
    public boolean exceedsRunBudget(int tokens, int cents) {
        if (maxTokensPerRun != null && tokens > maxTokensPerRun) {
            return true;
        }
        if (maxCentsPerRun != null && cents > maxCentsPerRun) {
            return true;
        }
        return false;
    }

    /** 检查是否超过每日预算（增量检查）。 */
    public boolean exceedsDailyBudget(long addedTokens, long addedCents) {
        long newDailyTokens = (currentDailyTokens != null ? currentDailyTokens : 0) + addedTokens;
        long newDailyCents = (currentDailyCents != null ? currentDailyCents : 0) + addedCents;

        if (maxTokensDaily != null && newDailyTokens > maxTokensDaily) {
            return true;
        }
        if (maxCentsDaily != null && newDailyCents > maxCentsDaily) {
            return true;
        }
        return false;
    }

    /** 检查是否超过每月预算（增量检查）。 */
    public boolean exceedsMonthlyBudget(long addedTokens, long addedCents) {
        long newMonthlyTokens = (currentMonthlyTokens != null ? currentMonthlyTokens : 0) + addedTokens;
        long newMonthlyCents = (currentMonthlyCents != null ? currentMonthlyCents : 0) + addedCents;

        if (maxTokensMonthly != null && newMonthlyTokens > maxTokensMonthly) {
            return true;
        }
        if (maxCentsMonthly != null && newMonthlyCents > maxCentsMonthly) {
            return true;
        }
        return false;
    }

    /** 是否需要重置日统计（跨天）。 */
    public boolean needsDailyReset() {
        return lastResetDate == null || !lastResetDate.equals(LocalDate.now());
    }

    /** 是否需要重置月统计（跨月）。 */
    public boolean needsMonthlyReset() {
        if (lastResetDate == null) {
            return true;
        }
        LocalDate now = LocalDate.now();
        return lastResetDate.getMonth() != now.getMonth() || lastResetDate.getYear() != now.getYear();
    }

    /** 重置日统计。 */
    public AiModelBudget resetDaily() {
        return new AiModelBudget(
            id,
            organizationId,
            capability,
            provider,
            maxTokensPerRun,
            maxTokensDaily,
            maxTokensMonthly,
            maxCentsPerRun,
            maxCentsDaily,
            maxCentsMonthly,
            0L,   // reset
            0L,   // reset
            currentMonthlyTokens,  // 不变
            currentMonthlyCents,   // 不变
            LocalDate.now(),
            version,
            enabled,
            createdAt,
            Instant.now()
        );
    }

    /** 重置月统计。 */
    public AiModelBudget resetMonthly() {
        return new AiModelBudget(
            id,
            organizationId,
            capability,
            provider,
            maxTokensPerRun,
            maxTokensDaily,
            maxTokensMonthly,
            maxCentsPerRun,
            maxCentsDaily,
            maxCentsMonthly,
            currentDailyTokens,    // 不变
            currentDailyCents,     // 不变
            0L,   // reset
            0L,   // reset
            LocalDate.now(),
            version,
            enabled,
            createdAt,
            Instant.now()
        );
    }

    /** 累加用量。 */
    public AiModelBudget accumulate(long addedTokens, long addedCents) {
        return new AiModelBudget(
            id,
            organizationId,
            capability,
            provider,
            maxTokensPerRun,
            maxTokensDaily,
            maxTokensMonthly,
            maxCentsPerRun,
            maxCentsDaily,
            maxCentsMonthly,
            (currentDailyTokens != null ? currentDailyTokens : 0) + addedTokens,
            (currentDailyCents != null ? currentDailyCents : 0) + addedCents,
            (currentMonthlyTokens != null ? currentMonthlyTokens : 0) + addedTokens,
            (currentMonthlyCents != null ? currentMonthlyCents : 0) + addedCents,
            LocalDate.now(),
            version,
            enabled,
            createdAt,
            Instant.now()
        );
    }
}
