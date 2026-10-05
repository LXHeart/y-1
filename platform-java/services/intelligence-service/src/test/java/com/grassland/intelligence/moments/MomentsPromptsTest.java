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
		assertThatThrownBy(() -> MomentsStyle.fromKey("viral")).isInstanceOf(IntelligenceException.class)
				.hasMessage("朋友圈风格不合法");
	}

	@Test
	void nullStyleKeyRejected() {
		assertThatThrownBy(() -> MomentsStyle.fromKey(null)).isInstanceOf(IntelligenceException.class)
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
		assertThat(MomentsPrompts.system(MomentsStyle.LIFESTYLE, 0).content()).contains("空数组");
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

	// ---------- 任务书 #108 C-01：朋友圈可自然结束、探店须确认到店（TC-C01-001，RULE-002）----------

	@Test
	void systemPromptAllowsNaturalEndingWithoutMandatedInteraction() {
		String system = MomentsPrompts.system(MomentsStyle.LIFESTYLE, 2).content();
		assertThat(system).contains("自然结束");
		assertThat(system).contains("不强制互动表达");
		// 旧「结尾自然带互动表达（约起 / 点赞 / 评论）」式强制互动收尾已消除
		assertThat(system).doesNotContain("结尾自然带互动表达");
		assertThat(system).doesNotContain("约起 / 点赞 / 评论");
	}

	@Test
	void storeVisitStyleRequiresConfirmedVisitBeforeFirstPerson() {
		String system = MomentsPrompts.system(MomentsStyle.STORE_VISIT, 0).content();
		assertThat(system).contains("仅在用户确认本人到店时以第一人称写体验");
		assertThat(system).contains("不得写成亲自探店");
		// 旧「以第一人称到店体验展开」无条件自述已消除
		assertThat(system).doesNotContain("以第一人称到店体验展开");
	}

	@Test
	void emojiUsageIsOptionalNotMandated() {
		String system = MomentsPrompts.system(MomentsStyle.FRIENDS_SHARE, 1).content();
		assertThat(system).contains("Emoji 可用可不用");
		assertThat(system).doesNotContain("少量 Emoji 增强生活感");
		// 熟人分享不预设亲昵称呼
		assertThat(system).doesNotContain("口吻亲近");
	}
}
