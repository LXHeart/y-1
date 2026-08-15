package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.ai.ChatMessage;
import org.junit.jupiter.api.Test;

/**
 * 视频脚本提示词的平台适配注入（PRD §4.7 朋友圈：精简文案、九宫格顺序和互动表达）。
 * 仅朋友圈注入熟人分享指引；其它平台 prompt 保持逐字不变（零回归）。
 */
class VideoScriptPromptsTest {

    @Test
    void momentsTargetInjectsMomentsAdaptation() {
        ChatMessage system = VideoScriptPrompts.system("生活化", "餐饮", "moments");
        assertThat(system.content()).contains("短视频脚本策划师");
        assertThat(system.content()).contains("朋友圈");
        assertThat(system.content()).contains("熟人");
        assertThat(system.content()).contains("互动");
    }

    @Test
    void nonMomentsPlatformKeepsPromptUnchanged() {
        String douyin = VideoScriptPrompts.system("生活化", "餐饮", "douyin").content();
        assertThat(douyin).contains("短视频脚本策划师");
        assertThat(douyin).doesNotContain("朋友圈");

        String kuaishou = VideoScriptPrompts.system("生活化", "餐饮", "kuaishou").content();
        assertThat(kuaishou).doesNotContain("朋友圈");
    }

    @Test
    void nullPlatformKeepsPromptUnchanged() {
        assertThat(VideoScriptPrompts.system("生活化", "餐饮", null).content())
                .doesNotContain("朋友圈");
    }
}
