package com.grassland.intelligence.moments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.security.IntelligenceException;
import org.junit.jupiter.api.Test;

/** 朋友圈提示词与风格模板（PRD §4.4 朋友圈图片+文字 / §4.7 朋友圈适配行）。 */
class MomentsPromptsTest {

    @Test
    void resolvesFourStyleKeysWithChineseLabels() {
        assertThat(MomentsStyle.fromKey("lifestyle").label()).isEqualTo("生活化");
        assertThat(MomentsStyle.fromKey("event").label()).isEqualTo("活动通知");
        assertThat(MomentsStyle.fromKey("store-visit").label()).isEqualTo("到店体验");
        assertThat(MomentsStyle.fromKey("friends-share").label()).isEqualTo("朋友分享");
    }

    @Test
    void rejectsUnknownStyleWith400() {
        assertThatThrownBy(() -> MomentsStyle.fromKey("viral"))
                .isInstanceOf(IntelligenceException.class)
                .hasMessage("朋友圈风格不合法");
    }

    @Test
    void nullStyleKeyRejected() {
        assertThatThrownBy(() -> MomentsStyle.fromKey(null))
                .isInstanceOf(IntelligenceException.class)
                .hasMessage("朋友圈风格不合法");
    }

    @Test
    void systemPromptCarriesStyleLabelRulesAndJsonContract() {
        ChatMessage system = MomentsPrompts.system(MomentsStyle.STORE_VISIT, 3);
        assertThat(system.content()).contains("到店体验");
        assertThat(system.content()).contains("九宫格");
        assertThat(system.content()).contains("不使用话题标签");
        assertThat(system.content()).contains("\"copy\"");
        assertThat(system.content()).contains("\"imageOrder\"");
        assertThat(system.content()).contains("\"captions\"");
    }

    @Test
    void systemPromptStatesEmptyArraysWhenNoImages() {
        assertThat(MomentsPrompts.system(MomentsStyle.LIFESTYLE, 0).content())
                .contains("空数组");
    }

    @Test
    void userPromptCarriesTopicAndFeelings() {
        String prompt = MomentsPrompts.user("新店开业", "周末人流不错");
        assertThat(prompt).contains("新店开业");
        assertThat(prompt).contains("周末人流不错");
    }

    @Test
    void userPromptWithoutFeelingsOmitsField() {
        String prompt = MomentsPrompts.user("周年庆", null);
        assertThat(prompt).contains("周年庆");
        assertThat(prompt).doesNotContain("补充感受");
    }
}
