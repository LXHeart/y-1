package com.grassland.marketplace.reputation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 管理端策略的边界与跨等级单调性校验。 */
final class ReputationPolicyValidator {

    private static final int MAX_BENEFITS = 16;
    private static final int MAX_BENEFIT_LENGTH = 128;

    private ReputationPolicyValidator() {}

    static List<ReputationLevelRule> requireValid(UpdateReputationPolicyRequest request) {
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 1) {
            throw new IllegalArgumentException("expectedVersion 必须大于等于 1");
        }
        if (request.levels() == null || request.levels().size() != 5) {
            throw new IllegalArgumentException("levels 必须完整包含 Lv1-Lv5");
        }
        List<ReputationLevelRule> rules = request.levels().stream()
                .map(ReputationPolicyValidator::validateRule)
                .sorted(Comparator.comparingInt(ReputationLevelRule::levelNumber))
                .toList();
        Set<Integer> numbers = new HashSet<>();
        rules.forEach(rule -> numbers.add(rule.levelNumber()));
        if (numbers.size() != 5 || !numbers.containsAll(List.of(1, 2, 3, 4, 5))) {
            throw new IllegalArgumentException("levels 必须完整且无重复");
        }
        validateBaseline(rules.getFirst());
        validateMonotonicity(rules);
        return rules;
    }

    private static ReputationLevelRule validateRule(ReputationLevelRuleInput input) {
        if (input == null) {
            throw new IllegalArgumentException("level rule 不能为空");
        }
        requirePrimitiveFields(input);
        if (input.levelNumber() < 1 || input.levelNumber() > 5) {
            throw new IllegalArgumentException("levelNumber 必须在 1-5");
        }
        String expectedCode = "Lv" + input.levelNumber();
        if (!expectedCode.equals(input.level())) {
            throw new IllegalArgumentException("level 必须与 levelNumber 一致");
        }
        String title = requireText(input.title(), 32, "title");
        if (input.minCompleted() < 0 || input.minCompleted() > 1_000_000) {
            throw new IllegalArgumentException("minCompleted 超出范围");
        }
        if (!Double.isFinite(input.minCompletionRate())
                || input.minCompletionRate() < 0 || input.minCompletionRate() > 1) {
            throw new IllegalArgumentException("minCompletionRate 必须在 0-1");
        }
        if (input.minAverageScore() != null
                && (!Double.isFinite(input.minAverageScore())
                || input.minAverageScore() < 0 || input.minAverageScore() > 5)) {
            throw new IllegalArgumentException("minAverageScore 必须在 0-5");
        }
        boolean lv5 = input.levelNumber() == 5;
        if (input.inviteOnly() != lv5 || input.judgeEligible() != lv5) {
            throw new IllegalArgumentException("仅 Lv5 可以邀请制并具备审判官资格");
        }
        if (input.taskPriorityWeight() < 1 || input.taskPriorityWeight() > 10_000) {
            throw new IllegalArgumentException("taskPriorityWeight 超出范围");
        }
        if (input.settlementDelayDays() < 0 || input.settlementDelayDays() > 30) {
            throw new IllegalArgumentException("settlementDelayDays 超出范围");
        }
        if (input.commissionBonusBps() < 0 || input.commissionBonusBps() > 10_000) {
            throw new IllegalArgumentException("commissionBonusBps 超出范围");
        }
        if (input.aiQuotaMultiplierBps() < 1_000 || input.aiQuotaMultiplierBps() > 100_000) {
            throw new IllegalArgumentException("aiQuotaMultiplierBps 超出范围");
        }
        List<String> benefits = validateBenefits(input.benefits());
        return new ReputationLevelRule(input.levelNumber(), expectedCode, title,
                input.minCompleted(), input.minCompletionRate(), input.minAverageScore(),
                input.inviteOnly(), input.judgeEligible(), input.taskPriorityWeight(),
                input.settlementDelayDays(), input.commissionBonusBps(),
                input.aiQuotaMultiplierBps(), input.premiumSupport(), benefits);
    }

    private static void requirePrimitiveFields(ReputationLevelRuleInput input) {
        if (input.levelNumber() == null) {
            throw new IllegalArgumentException("缺少 levelNumber");
        }
        if (input.minCompleted() == null) {
            throw new IllegalArgumentException("缺少 minCompleted");
        }
        if (input.minCompletionRate() == null) {
            throw new IllegalArgumentException("缺少 minCompletionRate");
        }
        if (input.inviteOnly() == null) {
            throw new IllegalArgumentException("缺少 inviteOnly");
        }
        if (input.judgeEligible() == null) {
            throw new IllegalArgumentException("缺少 judgeEligible");
        }
        if (input.taskPriorityWeight() == null) {
            throw new IllegalArgumentException("缺少 taskPriorityWeight");
        }
        if (input.settlementDelayDays() == null) {
            throw new IllegalArgumentException("缺少 settlementDelayDays");
        }
        if (input.commissionBonusBps() == null) {
            throw new IllegalArgumentException("缺少 commissionBonusBps");
        }
        if (input.aiQuotaMultiplierBps() == null) {
            throw new IllegalArgumentException("缺少 aiQuotaMultiplierBps");
        }
        if (input.premiumSupport() == null) {
            throw new IllegalArgumentException("缺少 premiumSupport");
        }
    }

    private static List<String> validateBenefits(List<String> values) {
        if (values == null || values.size() > MAX_BENEFITS) {
            throw new IllegalArgumentException("benefits 最多 16 项");
        }
        List<String> normalized = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            String benefit = requireText(value, MAX_BENEFIT_LENGTH, "benefit");
            if (!unique.add(benefit)) {
                throw new IllegalArgumentException("benefits 不可重复");
            }
            normalized.add(benefit);
        }
        return List.copyOf(normalized);
    }

    private static void validateBaseline(ReputationLevelRule lv1) {
        if (lv1.minCompleted() != 0 || lv1.minCompletionRate() != 0 || lv1.minAverageScore() != null) {
            throw new IllegalArgumentException("Lv1 必须是无门槛基线");
        }
    }

    private static void validateMonotonicity(List<ReputationLevelRule> rules) {
        for (int index = 1; index < rules.size(); index++) {
            ReputationLevelRule lower = rules.get(index - 1);
            ReputationLevelRule higher = rules.get(index);
            if (higher.minCompleted() < lower.minCompleted()
                    || higher.minCompletionRate() < lower.minCompletionRate()
                    || scoreBelow(higher.minAverageScore(), lower.minAverageScore())
                    || higher.taskPriorityWeight() < lower.taskPriorityWeight()
                    || higher.settlementDelayDays() > lower.settlementDelayDays()
                    || higher.commissionBonusBps() < lower.commissionBonusBps()
                    || higher.aiQuotaMultiplierBps() < lower.aiQuotaMultiplierBps()
                    || (lower.premiumSupport() && !higher.premiumSupport())) {
                throw new IllegalArgumentException("等级阈值与权益必须随等级单调增强");
            }
        }
    }

    private static boolean scoreBelow(Double higher, Double lower) {
        return lower != null && (higher == null || higher < lower);
    }

    private static String requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " 过长");
        }
        return normalized;
    }
}
