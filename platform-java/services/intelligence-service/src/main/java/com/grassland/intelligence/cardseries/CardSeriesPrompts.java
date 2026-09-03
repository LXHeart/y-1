package com.grassland.intelligence.cardseries;

/**
 * 系列图卡 prompt 契约（任务书 #54 S1.2；2026-09-02 文字策略改版）。
 *
 * <p>
 * 文字渲染策略（用户拍板）：图像模型<b>直接把标题/要点绘制进画面</b>——字图一体、由模型排版， 不再走「无字底图 + 导出 canvas
 * 叠字」旧链（旧链的刻意留白约束牺牲了画面密度，出图空素）。 依 OpenAI gpt-image
 * 官方指南：图内文字用引号精确引用、显式要求清晰排版与「只出现一次」； 依 MiniMax
 * 官方指南：画面描述要具体可画（主体/细节/场景/构图/光线/质感），拆卡侧按此产出。 caption 仍是发布文案，不进图。
 */
final class CardSeriesPrompts {

	private CardSeriesPrompts() {
	}

	static String systemPlan(CardSeriesService.PlanInput input) {
		String palette = input.paletteText() == null ? "" : "配色基调：" + input.paletteText() + "。";
		return """
				你是面向「%s」平台的系列图文卡片策划，把用户提供的长图文内容拆解为 %d 张风格统一的卡片计划。
				视觉风格：%s。画面布局：%s。%s
				规则：
				1. 第 1 张是封面卡：从内容中提炼最强钩子（悬念/数字/利益点）作主标题。
				2. 其余卡片按内容的自然段落/要点顺序拆分，每张承载一个独立要点，不互相重复、不遗漏关键信息。
				3. 每张卡 bullets 不超过 5 条，每条不超过 20 字，尽量沿用原文表述。标题与要点将被直接绘制在画面中，务必简短有力。
				4. illustration 是交给图像生成模型的完整画面描述，决定整张卡的画面质量——要具体可画，依次写清：主体及其外观细节、场景环境、构图与视角（特写/俯拍/平视）、光线与氛围、质感媒介；100-200 字，与视觉风格一致，不要出现文字排版要求。
				5. caption 是发布时该卡的配文，从原文对应段落提炼，口语化，可带 emoji。
				只输出 JSON（可包 ```json 代码块）：
				{"cards":[{"title":"...","bullets":["..."],"illustration":"...","caption":"..."}]}
				cards 数量必须等于 %d。"""
				.formatted(platformLabel(input.platform()), input.cardCount(), input.styleText(), input.layoutText(),
						palette, input.cardCount());
	}

	static String userPlan(CardSeriesService.PlanInput input) {
		return "以下是已生成的长图文内容，请拆解为卡片计划：\n\n" + input.content();
	}

	/**
	 * 单卡生图 prompt：用途定位 → 画面内容（结构化描述）→ 风格/布局/配色 → 图内文字（引号精确引用） → 排版约束。首卡
	 * revised_prompt 作风格锚（第 2 卡起）。
	 */
	static String cardPrompt(CardSeriesService.GenerateInput input, CardSeriesService.CardPlan card, int index,
			String styleAnchor) {
		StringBuilder prompt = new StringBuilder();
		prompt.append("生成一张竖版社交媒体图文卡片，标题与要点直接绘制在画面中。");
		if (index == 0) {
			prompt.append("这是系列封面卡，画面需有最强视觉冲击力。");
		} else {
			prompt.append("这是系列第 ").append(index + 1).append(" 张内容卡。");
			if (styleAnchor != null && !styleAnchor.isBlank()) {
				prompt.append("为保持系列视觉一致，参考首图的风格基调：").append(truncate(styleAnchor, 400)).append("。");
			}
		}
		prompt.append("画面：").append(truncate(card.illustration(), 500));
		prompt.append("。视觉风格：").append(input.styleText()).append("。画面布局：").append(input.layoutText());
		if (input.paletteText() != null && !input.paletteText().isBlank()) {
			prompt.append("。配色基调：").append(input.paletteText());
		}
		prompt.append("。绘制以下文字：主标题\"").append(card.title()).append("\"");
		if (card.bullets() != null && !card.bullets().isEmpty()) {
			prompt.append("；要点 ").append(card.bullets().size()).append(" 条：");
			for (int i = 0; i < card.bullets().size(); i++) {
				if (i > 0) {
					prompt.append("、");
				}
				prompt.append("\"").append(card.bullets().get(i)).append("\"");
			}
		}
		prompt.append("。排版要求：标题醒目、要点逐条清晰可读，中文准确无误、逐字对应且每处文字只出现一次；" + "文字排版与插画协调融合，不遮挡画面主体；字体风格与整体视觉统一，无多余字符和水印。");
		return prompt.toString();
	}

	/** 平台标签（仅 prompt 语义用，不做能力校验——合法性由前端矩阵保证）。 */
	private static String platformLabel(String platform) {
		return switch (platform == null ? "" : platform) {
			case "xiaohongshu" -> "小红书";
			case "douyin" -> "抖音";
			case "dianping" -> "大众点评";
			case "moments" -> "微信朋友圈";
			case "wechat-official" -> "微信公众号";
			case "zhihu" -> "知乎";
			default -> "社交媒体";
		};
	}

	private static String truncate(String value, int max) {
		if (value == null) {
			return "";
		}
		return value.length() <= max ? value : value.substring(0, max);
	}
}
