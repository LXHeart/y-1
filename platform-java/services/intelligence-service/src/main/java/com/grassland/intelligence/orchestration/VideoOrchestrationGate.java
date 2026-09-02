package com.grassland.intelligence.orchestration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 编排开关（任务书 #66 卡A1 起、A4 收口）：{@code ai.video-production.orchestration: legacy|temporal}。
 * A4 后默认 temporal——生成驱动全走 workflow；legacy 枚举保留一版作回滚（回滚后由收养清扫兜住存量）。
 */
@Component
public class VideoOrchestrationGate {

    public static final String MODE_LEGACY = "legacy";
    public static final String MODE_TEMPORAL = "temporal";

    private final String mode;

    public VideoOrchestrationGate(@Value("${ai.video-production.orchestration:temporal}") String mode) {
        this.mode = mode == null || mode.isBlank() ? MODE_LEGACY : mode.trim().toLowerCase();
        if (!MODE_LEGACY.equals(this.mode) && !MODE_TEMPORAL.equals(this.mode)) {
            throw new IllegalArgumentException("ai.video-production.orchestration 仅支持 legacy|temporal，收到: "
                    + this.mode);
        }
    }

    public boolean temporal() {
        return MODE_TEMPORAL.equals(mode);
    }

    public String mode() {
        return mode;
    }
}
