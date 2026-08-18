package com.grassland.intelligence.embedding;

import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

/**
 * 语义排序数学（任务书 #33）：余弦 [-1,1] 归一化到 0-100，60/40 语义/规则融合，
 * 缺向量素材仅保留规则份额；排序键 finalScore DESC, ruleScore DESC, updatedAt DESC, id ASC。
 */
public final class SemanticRanker {

    private static final double SEMANTIC_WEIGHT = 0.60;
    private static final double RULE_WEIGHT = 0.40;

    private SemanticRanker() {}

    public static int semanticScore(double cosine) {
        double clampedCosine = Math.max(-1.0, Math.min(1.0, cosine));
        return (int) Math.round(Math.clamp((clampedCosine + 1.0) / 2.0, 0.0, 1.0) * 100.0);
    }

    public static int combine(int semanticScore, int ruleScore) {
        return (int) Math.round(SEMANTIC_WEIGHT * semanticScore + RULE_WEIGHT * ruleScore);
    }

    /** 语义检索运行中缺少当前向量的素材：只按规则份额参与同一次融合排序。 */
    public static int rulesOnlyInSemanticRun(int ruleScore) {
        return (int) Math.round(RULE_WEIGHT * ruleScore);
    }

    public static Comparator<Ranked> order() {
        return Comparator.comparingInt(Ranked::finalScore).reversed()
                .thenComparing(Comparator.comparingInt(Ranked::ruleScore).reversed())
                .thenComparing(Comparator.comparing(Ranked::updatedAt).reversed())
                .thenComparing(Ranked::id);
    }

    public record Ranked(UUID id, int finalScore, int ruleScore, Instant updatedAt) {}
}
