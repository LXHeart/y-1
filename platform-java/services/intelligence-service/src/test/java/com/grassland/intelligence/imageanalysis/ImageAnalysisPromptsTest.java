package com.grassland.intelligence.imageanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.imageanalysis.ImageAnalysisPrompts.ImageReviewInput;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 锁定图片评价 prompts 的关键字符串（忠实移植 legacy qwen-provider.ts +
 * image-review-style.service.ts）。
 */
class ImageAnalysisPromptsTest {

	@Test
	void draftPromptContainsLengthRuleAndJsonFormat() {
		String prompt = ImageAnalysisPrompts.buildImageReviewPrompt(input(120, null, "taobao", null));
		// AI内容中心改造-01：字数要求服从事实约束——目标贴近而非「不能少于」，资料不足不得凑字数。
		assertThat(prompt).contains("目标字数尽量贴近 120 字");
		assertThat(prompt).contains("资料不足时保持简短，不得为凑字数虚构细节");
		assertThat(prompt).contains("最长不要超过 " + ImageAnalysisPrompts.calculateImageReviewMaxLength(120) + " 字");
		assertThat(prompt).contains("\"review\": \"生成的评价文案\"");
		assertThat(prompt).contains("去AI化要求");
		assertThat(prompt).contains("用户没有补充感受");
		assertThat(prompt).contains("不得默认好评");
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
		String prompt = ImageAnalysisPrompts.buildImageReviewOptimizationPrompt(input(120, null, "taobao", null),
				"初稿内容", 1);
		assertThat(prompt).contains("第 1 轮优化");
		assertThat(prompt).contains("<<<初稿内容>>>");
		// 任务书 #108 C-01：条件式编辑——只改有可指认问题的地方，自然的句子保持原文不改
		assertThat(prompt).contains("只修改有可指认问题的地方");
		assertThat(prompt).contains("保持原文不改");
		// 显式重看原始材料（图片可见信息与用户确认的事实）
		assertThat(prompt).contains("先对照本次原始材料逐句检查");
	}

	@Test
	void styleRefinePromptMentionsPersonalStyle() {
		String prompt = ImageAnalysisPrompts.buildImageReviewStyleRefinementPrompt(input(100, null, "taobao", null),
				"待调整");
		assertThat(prompt).contains("个人风格优化");
		assertThat(prompt).contains("<<<待调整>>>");
	}

	// ---------- 任务书 #108 C-01：去AI化改条件式（TC-C01-001，RULE-003）----------

	@Test
	void humanizerRulesAreConditionalAndKeepContextualSurpriseWords() {
		String prompt = ImageAnalysisPrompts.buildImageReviewPrompt(input(120, null, "taobao", null));
		// 标题保留「去AI化要求」，但改为条件式：不再「每一条都是红线」式一刀切
		assertThat(prompt).contains("去AI化要求（条件式");
		assertThat(prompt).doesNotContain("每一条都是红线");
		// 「居然」「竟然」有具体语境即可保留，不再进入禁词表
		assertThat(prompt).contains("\"居然\"\"竟然\"表达真实惊讶且有具体语境").contains("可以保留");
		assertThat(prompt).doesNotContain("禁止过度营销感词汇");
		assertThat(prompt).doesNotContain("\"居然\"、\"竟然\"");
		// 排比/破折号有语境可保留；自然的原文不为去AI味强改
		assertThat(prompt).contains("排比、破折号有具体语境即可保留");
		assertThat(prompt).contains("不为去AI味强改自然的原文");
	}

	@Test
	void dianpingRulesKeepFactBoundaryAndConditionalEmoji() {
		String prompt = ImageAnalysisPrompts.buildImageReviewPrompt(input(150, null, "dianping", null));
		// 点评只凭确认材料写体验（RULE-002）；无确认消费感受不得写成亲自体验
		assertThat(prompt).contains("没有用户确认的消费感受时，不得写成亲自到店、购买或享用后的体验");
		// 点评格式契约不变（标题/正文/标签三字段 JSON）
		assertThat(prompt).contains("\"title\": \"10-20字的标题\"");
		assertThat(prompt).contains("\"tags\":");
		// emoji 为建议性表述（最多 1-2 个点缀），非逐段强制
		assertThat(prompt).doesNotContain("每段 1-2 个");
	}

	@Test
	void stylePreferencesAppendixAppendedToPromptWhenPresent() {
		String appendix = ImageAnalysisPrompts.buildStylePreferenceAppendix(List.of("偏好短句", "不用 emoji"));
		String prompt = ImageAnalysisPrompts.buildImageReviewPrompt(input(100, null, "taobao", appendix));
		assertThat(appendix).contains("用户个人风格偏好（请在生成中体现这些偏好）");
		assertThat(appendix).contains("- 偏好短句");
		assertThat(prompt).contains(appendix);
		// 改造-01：事实底线在风格偏好之后收尾——文风偏好不能覆盖事实约束。
		assertThat(prompt).endsWith("文风偏好与字数要求不能覆盖事实约束。");
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
		String summary = ImageAnalysisPrompts.buildStyleSummaryPrompt(ImageAnalysisPrompts.prettyJson(snap("原评价")),
				ImageAnalysisPrompts.prettyJson(snap("编辑后")));
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
