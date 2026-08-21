package com.grassland.intelligence.contentsafety;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 内容安全阈值配置（任务书 #45 登记 / ADR-D16）：SimHash 查重、文内重复、L2 深检 最小长度——
 * 运营按误报/漏报反馈经 env（`CONTENT_SAFETY_*`）或部署配置调优，无需改代码发版。
 * 默认值 = 代码写死时期的取值（Hamming 16 / 重复率 0.30 / 深检 200 字），行为零变更。
 */
@Component
@ConfigurationProperties(prefix = "content-safety")
public class ContentSafetyProperties {

    private final Originality originality = new Originality();

    /** L2 深检最小文本长度（字符；ADR-D16 D5「长文本」阈值，短文本仅 L1）。 */
    private int deepCheckMinChars = 200;

    public static class Originality {

        /** SimHash 查重最大 Hamming 距离（0-64；越小越严——更近的指纹才算重复）。 */
        private int maxHammingDistance = 16;

        /** 文内重复率上限（0-1；超过才报 low_originality advisory）。 */
        private double maxRepetitionRate = 0.30d;

        public int getMaxHammingDistance() {
            return maxHammingDistance;
        }

        public void setMaxHammingDistance(int maxHammingDistance) {
            this.maxHammingDistance = maxHammingDistance;
        }

        public double getMaxRepetitionRate() {
            return maxRepetitionRate;
        }

        public void setMaxRepetitionRate(double maxRepetitionRate) {
            this.maxRepetitionRate = maxRepetitionRate;
        }
    }

    public Originality getOriginality() {
        return originality;
    }

    public int getDeepCheckMinChars() {
        return deepCheckMinChars;
    }

    public void setDeepCheckMinChars(int deepCheckMinChars) {
        this.deepCheckMinChars = deepCheckMinChars;
    }

    @PostConstruct
    void validate() {
        if (originality.maxHammingDistance < 0 || originality.maxHammingDistance > 64) {
            throw new IllegalStateException("content-safety.originality.max-hamming-distance 须在 0-64");
        }
        if (originality.maxRepetitionRate < 0d || originality.maxRepetitionRate > 1d) {
            throw new IllegalStateException("content-safety.originality.max-repetition-rate 须在 0-1");
        }
        if (deepCheckMinChars < 1) {
            throw new IllegalStateException("content-safety.deep-check-min-chars 须 ≥ 1");
        }
    }
}
