package com.grassland.marketplace.reputation;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** 一个不可变的等级策略快照；版本在整组五级规则更新后统一递增。 */
public record ReputationPolicy(long version, List<ReputationLevelRule> levels, Instant updatedAt) {

    public ReputationPolicy {
        levels = levels.stream().sorted(Comparator.comparingInt(ReputationLevelRule::levelNumber)).toList();
        if (levels.size() != RecommenderLevel.values().length) {
            throw new IllegalArgumentException("等级策略必须包含 Lv1-Lv5");
        }
        for (int index = 0; index < levels.size(); index++) {
            if (levels.get(index).levelNumber() != index + 1) {
                throw new IllegalArgumentException("等级策略必须完整且无重复");
            }
        }
    }

    public ReputationEvaluation evaluate(ReputationStats stats, boolean lv5Admitted) {
        return evaluate(stats, lv5Admitted, Instant.now());
    }

    public ReputationEvaluation evaluate(ReputationStats stats, boolean lv5Admitted, Instant now) {
        ReputationLevelRule calculated = levels.getFirst();
        for (ReputationLevelRule rule : levels) {
            if (meets(stats, rule)) {
                calculated = rule;
            }
        }
        ReputationLevelRule effective = calculated;
        if (calculated.recommenderLevel() == RecommenderLevel.LV5 && !lv5Admitted) {
            effective = levels.get(RecommenderLevel.LV4.ordinal());
        }
        boolean inactiveDowngraded = stats.lastActiveAt() != null
                && !stats.lastActiveAt().isAfter(now.minus(Duration.ofDays(30)))
                && calculated.recommenderLevel() != RecommenderLevel.LV1;
        if (inactiveDowngraded) {
            ReputationLevelRule inactivityCap = levels.get(calculated.levelNumber() - 2);
            if (effective.levelNumber() > inactivityCap.levelNumber()) {
                effective = inactivityCap;
            }
        }
        return new ReputationEvaluation(
                calculated.recommenderLevel(),
                effective.recommenderLevel(),
                effective.judgeEligible() && effective.recommenderLevel() == RecommenderLevel.LV5,
                effective.taskPriorityWeight(),
                effective.settlementDelayDays(),
                effective.commissionBonusBps(),
                effective.aiQuotaMultiplierBps(),
                effective.premiumSupport(),
                inactiveDowngraded,
                effective.benefits());
    }

    public ReputationLevelRule ruleFor(RecommenderLevel level) {
        return levels.get(level.ordinal());
    }

    private static boolean meets(ReputationStats stats, ReputationLevelRule rule) {
        if (stats.completedCount() < rule.minCompleted()
                || stats.completionRate() < rule.minCompletionRate()) {
            return false;
        }
        return rule.minAverageScore() == null
                || (stats.averageScore() != null && stats.averageScore() >= rule.minAverageScore());
    }

    public static ReputationPolicy defaults() {
        return new ReputationPolicy(1, List.of(
                new ReputationLevelRule(1, "Lv1", "新手草友", 0, 0, null,
                        false, false, 100, 2, 0, 10_000, false, List.of("基础任务")),
                new ReputationLevelRule(2, "Lv2", "活跃草友", 6, 0.80, null,
                        false, false, 110, 2, 0, 10_000, false, List.of("更多任务")),
                new ReputationLevelRule(3, "Lv3", "优质草友", 21, 0.85, 4.0,
                        false, false, 120, 2, 300, 15_000, false, List.of("优先推荐")),
                new ReputationLevelRule(4, "Lv4", "金牌草友", 51, 0.90, 4.5,
                        false, false, 140, 2, 500, 15_000, true, List.of("专属任务", "专属支持")),
                new ReputationLevelRule(5, "Lv5", "草场达人", 100, 0.95, 4.8,
                        true, true, 160, 1, 1_000, 15_000, true,
                        List.of("审判官资格", "T+1 优先结算"))), Instant.EPOCH);
    }
}
