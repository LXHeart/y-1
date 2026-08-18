package com.grassland.intelligence.embedding;

import java.util.List;

/** 余弦相似度：维度一致、元素有限、范数非零，否则拒绝比较（视为 Provider 非法输出）。 */
public final class CosineSimilarity {

    private CosineSimilarity() {}

    public static double cosine(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.isEmpty() || left.size() != right.size()) {
            throw new IllegalArgumentException("余弦相似度要求两个等长非空向量");
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.size(); i++) {
            double a = left.get(i);
            double b = right.get(i);
            if (!Double.isFinite(a) || !Double.isFinite(b)) {
                throw new IllegalArgumentException("向量包含非有限元素");
            }
            dot += a * b;
            leftNorm += a * a;
            rightNorm += b * b;
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            throw new IllegalArgumentException("零向量无法计算余弦相似度");
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
