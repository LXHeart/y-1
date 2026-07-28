package com.grassland.intelligence.imageanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.imageanalysis.ImageAnalysisPrompts.ImageReviewInput;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 锁定图片评价 prompts 的关键字符串（忠实移植 legacy qwen-provider.ts + image-review-style.service.ts）。 */
class ImageAnalysisPromptsTest {

    @Test
    void draftPromptContainsLengthRuleAndJsonFormat() {
        String prompt = ImageAnalysisPrompts.buildImageReviewPrompt(input(120, null, "taobao", null));
        assertThat(prompt).contains("最终字数不能少于 120 字");
        assertThat(prompt).contains("最长不要超过 " + ImageAnalysisPrompts.calculateImageReviewMaxLength(120) + " 字");
        assertThat(prompt).contains("\"review\": \"生成的评价文案\"");
        assertThat(prompt).contains("去AI化要求");
        assertThat(prompt).contains("用户没有补充感受");
    }

    @Test
    void draftPromptInjectsFeelingsInUntrustedFence() {
        String prompt = ImageAnalysisPrompts.buildImageReviewPrompt(input(100, "看着挺新鲜的，包装也干净", "taobao", null));
        assertThat(prompt).contains("用户补充感受");
        assertThat(prompt).contains("<<<看着挺新鲜的，包装也干净>>>");
        assertThat(prompt).contains("请吸收这些感受");
    }

    @Test
    void dianpingPromptUsesTitleReviewTagsFormat() {
        String prompt = ImageAnalysisPrompts.buildImageReviewPrompt(input(150, null, "dianping", null));
        assertThat(prompt).contains("大众点评笔记");
        assertThat(prompt).contains("\"title\": \"10-20字的标题\"");
        assertThat(prompt).contains("\"tags\":");
    }

    @Test
    void optimizePromptContainsRoundNumberAndDraftFence() {
        String prompt = ImageAnalysisPrompts.buildImageReviewOptimizationPrompt(input(120, null, "taobao", null), "初稿内容", 1);
        assertThat(prompt).contains("第 1 轮优化");
        assertThat(prompt).contains("<<<初稿内容>>>");
        assertThat(prompt).contains("去掉明显的 AI 腔");
    }

    @Test
    void styleRefinePromptMentionsPersonalStyle() {
        String prompt = ImageAnalysisPrompts.buildImageReviewStyleRefinementPrompt(input(100, null, "taobao", null), "待调整");
        assertThat(prompt).contains("个人风格优化");
        assertThat(prompt).contains("<<<待调整>>>");
    }

    @Test
    void stylePreferencesAppendixAppendedToPromptWhenPresent() {
        String appendix = ImageAnalysisPrompts.buildStylePreferenceAppendix(List.of("偏好短句", "不用 emoji"));
        String prompt = ImageAnalysisPrompts.buildImageReviewPrompt(input(100, null, "taobao", appendix));
        assertThat(appendix).contains("用户个人风格偏好（请在生成中体现这些偏好）");
        assertThat(appendix).contains("- 偏好短句");
        assertThat(prompt).endsWith(appendix);
    }

    @Test
    void emptyPreferencesYieldEmptyAppendixAndNoInjection() {
        assertThat(ImageAnalysisPrompts.buildStylePreferenceAppendix(List.of())).isEmpty();
        assertThat(ImageAnalysisPrompts.buildStylePreferenceAppendix(null)).isEmpty();
        String prompt = ImageAnalysisPrompts.buildImageReviewPrompt(input(100, null, "taobao", ""));
        assertThat(prompt).doesNotContain("用户个人风格偏好");
    }

    @Test
    void styleSummaryAndOptimizePromptsContainKeyInstructions() {
        String summary = ImageAnalysisPrompts.buildStyleSummaryPrompt(
                ImageAnalysisPrompts.prettyJson(snap("原评价")), ImageAnalysisPrompts.prettyJson(snap("编辑后")));
        assertThat(summary).contains("写作风格分析助手");
        assertThat(summary).contains("修改前：");
        assertThat(summary).contains("修改后：");

        String optimize = ImageAnalysisPrompts.buildStyleOptimizePrompt(List.of("偏好 A", "偏好 B"));
        assertThat(optimize).contains("写作风格偏好整理助手");
        assertThat(optimize).contains("1. 偏好 A");
        assertThat(optimize).contains("2. 偏好 B");
    }

    @Test
    void maxLengthUsesTwentyPercentFloorTen() {
        assertThat(ImageAnalysisPrompts.calculateImageReviewMaxLength(20)).isEqualTo(30); // 20 + max(10, 4) = 30
        assertThat(ImageAnalysisPrompts.calculateImageReviewMaxLength(100)).isEqualTo(120); // 100 + 20
    }

    private static ImageReviewInput input(int reviewLength, String feelings, String platform, String stylePreferences) {
        return new ImageReviewInput(reviewLength, feelings, platform, stylePreferences);
    }

    private static StylePreferencesService.StyleSnapshot snap(String review) {
        return new StylePreferencesService.StyleSnapshot(review, null, null);
    }
}
