package com.grassland.marketplace.taskcatalog;

import java.util.Comparator;
import java.util.List;

/**
 * Versioned, single-metric commission ladder frozen with a task contract.
 *
 * <p>Each tier is a fixed payout for reaching its threshold. Tiers do not
 * accumulate: settlement selects the highest threshold that the verified
 * metric value reaches. A value below the first tier settles to zero.
 */
public record CommissionLadder(
        String policyVersion,
        String metricKey,
        List<Tier> tiers) {

    public static final int MAX_TIERS = 20;
    private static final int MAX_VERSION_LENGTH = 64;
    private static final int MAX_METRIC_KEY_LENGTH = 128;

    public CommissionLadder {
        policyVersion = required(policyVersion, MAX_VERSION_LENGTH, "佣金策略版本");
        metricKey = required(metricKey, MAX_METRIC_KEY_LENGTH, "佣金指标");
        if (!metricKey.matches("[a-zA-Z][a-zA-Z0-9_.-]*")) {
            throw new IllegalArgumentException("佣金指标只能包含字母、数字、点、下划线或连字符");
        }
        if (tiers == null || tiers.isEmpty()) {
            throw new IllegalArgumentException("阶梯佣金至少需要一个档位");
        }
        if (tiers.size() > MAX_TIERS) {
            throw new IllegalArgumentException("阶梯佣金最多支持 " + MAX_TIERS + " 个档位");
        }
        List<Tier> normalized = tiers.stream().map(Tier::normalize).sorted(Comparator.comparingLong(Tier::threshold)).toList();
        for (int i = 1; i < normalized.size(); i++) {
            Tier previous = normalized.get(i - 1);
            Tier current = normalized.get(i);
            if (previous.threshold() == current.threshold()) {
                throw new IllegalArgumentException("阶梯佣金档位阈值不能重复");
            }
            if (current.payoutCents() < previous.payoutCents()) {
                throw new IllegalArgumentException("阶梯佣金金额必须随档位升高而不下降");
            }
        }
        tiers = List.copyOf(normalized);
    }

    /** Returns the fixed payout for the highest tier reached by the metric. */
    public long payoutFor(long metricValue) {
        if (metricValue < 0) {
            throw new IllegalArgumentException("佣金指标值不能为负数");
        }
        long payout = 0;
        for (Tier tier : tiers) {
            if (tier.threshold() > metricValue) break;
            payout = tier.payoutCents();
        }
        return payout;
    }

    public long maximumPayoutCents() {
        return tiers.get(tiers.size() - 1).payoutCents();
    }

    /** The merchant must pre-fund at least the highest possible fixed payout. */
    public void validateReserve(Long bountyCents) {
        if (bountyCents == null || bountyCents < maximumPayoutCents()) {
            throw new IllegalArgumentException("阶梯佣金最高档必须有足额赏金预留");
        }
    }

    /** 派生态（有无档位），非契约字段——禁止序列化进 requirements 快照，否则读回视为未知属性而损坏。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isConfigured() {
        return !tiers.isEmpty();
    }

    private static String required(String value, int maxLength, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(label + "过长");
        }
        return normalized;
    }

    public record Tier(long threshold, long payoutCents) {
        public Tier {
            if (threshold < 0) {
                throw new IllegalArgumentException("阶梯阈值不能为负数");
            }
            if (payoutCents < 0) {
                throw new IllegalArgumentException("阶梯佣金不能为负数");
            }
        }

        private static Tier normalize(Tier value) {
            if (value == null) {
                throw new IllegalArgumentException("阶梯档位不能为空");
            }
            return value;
        }
    }
}
