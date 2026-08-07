package com.grassland.marketplace.reputation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReputationPolicyTest {

    @Test
    @DisplayName("默认 Lv3 AI 配额倍率为 +50%")
    void defaultLv3AiQuotaMultiplierIsFiftyPercentBonus() {
        assertThat(ReputationPolicy.defaults().ruleFor(RecommenderLevel.LV3).aiQuotaMultiplierBps())
                .isEqualTo(15_000);
    }

    @Test
    @DisplayName("运行时配置会改变自动等级阈值")
    void configurableThresholdsDriveCalculatedLevel() {
        ReputationPolicy defaults = ReputationPolicy.defaults();
        List<ReputationLevelRule> rules = new ArrayList<>(defaults.levels());
        ReputationLevelRule lv2 = rules.get(1);
        rules.set(1, new ReputationLevelRule(2, "Lv2", lv2.title(), 8, 0.80, null,
                false, false, lv2.taskPriorityWeight(), lv2.settlementDelayDays(),
                lv2.commissionBonusBps(), lv2.aiQuotaMultiplierBps(),
                lv2.premiumSupport(), lv2.benefits()));
        ReputationPolicy stricter = new ReputationPolicy(2, rules, Instant.now());

        ReputationStats sixPerfect = stats(6, 1.0, null);
        ReputationStats eightPerfect = stats(8, 1.0, null);

        assertThat(stricter.evaluate(sixPerfect, false).effectiveLevel()).isEqualTo(RecommenderLevel.LV1);
        assertThat(stricter.evaluate(eightPerfect, false).effectiveLevel()).isEqualTo(RecommenderLevel.LV2);
    }

    @Test
    @DisplayName("Lv5 同时要求必要指标与有效邀请，且 judgeEligible 由有效 Lv5 决定")
    void lv5RequiresMetricsAndAdmission() {
        ReputationPolicy policy = ReputationPolicy.defaults();
        ReputationStats lv5Metrics = stats(100, 0.96, 4.8);

        ReputationEvaluation notInvited = policy.evaluate(lv5Metrics, false);
        assertThat(notInvited.calculatedLevel()).isEqualTo(RecommenderLevel.LV5);
        assertThat(notInvited.effectiveLevel()).isEqualTo(RecommenderLevel.LV4);
        assertThat(notInvited.judgeEligible()).isFalse();

        ReputationEvaluation invited = policy.evaluate(lv5Metrics, true);
        assertThat(invited.effectiveLevel()).isEqualTo(RecommenderLevel.LV5);
        assertThat(invited.judgeEligible()).isTrue();
        assertThat(invited.benefits()).contains("审判官资格");
    }

    @Test
    @DisplayName("已有邀请但指标跌破 Lv5 时立即回落到自动等级")
    void admissionDoesNotOverrideMetrics() {
        ReputationEvaluation result = ReputationPolicy.defaults().evaluate(stats(99, 1.0, 5.0), true);

        assertThat(result.calculatedLevel()).isEqualTo(RecommenderLevel.LV4);
        assertThat(result.effectiveLevel()).isEqualTo(RecommenderLevel.LV4);
        assertThat(result.judgeEligible()).isFalse();
    }

    @Test
    @DisplayName("连续 30 天无活跃时自动降一级并即时撤去对应权益")
    void inactivityDowngradesOneLevelAndItsEntitlements() {
        Instant now = Instant.parse("2026-08-07T12:00:00Z");
        ReputationStats staleLv5 = stats(100, 0.96, 4.8, now.minusSeconds(30L * 24 * 60 * 60));
        ReputationStats activeLv5 = stats(100, 0.96, 4.8, now.minusSeconds(30L * 24 * 60 * 60 - 1));

        ReputationEvaluation stale = ReputationPolicy.defaults().evaluate(staleLv5, true, now);
        ReputationEvaluation active = ReputationPolicy.defaults().evaluate(activeLv5, true, now);

        assertThat(stale.calculatedLevel()).isEqualTo(RecommenderLevel.LV5);
        assertThat(stale.effectiveLevel()).isEqualTo(RecommenderLevel.LV4);
        assertThat(stale.judgeEligible()).isFalse();
        assertThat(stale.settlementDelayDays()).isEqualTo(2);
        assertThat(stale.commissionBonusBps()).isEqualTo(500);
        assertThat(active.effectiveLevel()).isEqualTo(RecommenderLevel.LV5);
    }

    private static ReputationStats stats(int completed, double rate, Double score) {
        return stats(completed, rate, score, Instant.now());
    }

    private static ReputationStats stats(int completed, double rate, Double score, Instant lastActiveAt) {
        int accepted = (int) Math.ceil(completed / rate);
        return new ReputationStats(accepted, completed, 0, 0, 0,
                score == null ? 0 : 10, score, null, lastActiveAt);
    }
}
