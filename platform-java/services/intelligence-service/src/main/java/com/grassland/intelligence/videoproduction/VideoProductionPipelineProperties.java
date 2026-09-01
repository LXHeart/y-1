package com.grassland.intelligence.videoproduction;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 分镜成片管线参数（任务书 #64 卡6）。与 {@code ai.video-generation}（旧 jobs 链 + worker
 * 调优）分家：这里只放新管线的业务参数。
 */
@Component
@ConfigurationProperties(prefix = "ai.video-production")
public class VideoProductionPipelineProperties {

    /** 每镜默认候选 take 数（P5：1-3，治理台经 yml 调）。 */
    private int defaultTakes = 2;

    /** 卡8：中间产物（take/audio 对象）清理天数。 */
    private int artifactRetentionDays = 7;

    public void validate() {
        if (defaultTakes < 1 || defaultTakes > 3) {
            throw new IllegalStateException("ai.video-production.default-takes 必须在 1-3 之间");
        }
    }

    public int getDefaultTakes() {
        return defaultTakes;
    }

    public void setDefaultTakes(int defaultTakes) {
        this.defaultTakes = defaultTakes;
    }

    public int getArtifactRetentionDays() {
        return artifactRetentionDays;
    }

    public void setArtifactRetentionDays(int artifactRetentionDays) {
        this.artifactRetentionDays = artifactRetentionDays;
    }
}
