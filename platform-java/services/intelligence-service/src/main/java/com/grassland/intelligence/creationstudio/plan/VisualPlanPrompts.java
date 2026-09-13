package com.grassland.intelligence.creationstudio.plan;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务书 #101 C101-05：视觉计划 prompt（自 {@code CardSeriesPrompts.systemPlan} 本地化——
 * 保留拆卡规则与字图一体约束，去掉运行命令；输出面向受控 PlanDocument 的最小字段集）。
 *
 * <p>
 * 策略语义：story=体验叙事；information=信息密度优先；visual=画面优先。模型只输出
 * role／文案／关键文字／sourceBlockIds；身份、position、layout 由服务端铸造与校验， 模型无法注入
 * itemId、cardId 或任意额外字段。
 */
final class VisualPlanPrompts {

	/** upstream_commit：prompt 模板版本溯源（本地化来源 CardSeriesPrompts 2026-09-02 文字策略版）。 */
	static final String UPSTREAM_VERSION = "visual-plan-prompts-1.0.0";

	private VisualPlanPrompts() {
	}

	static String system(String platformLabel, String recipeLabel, String strategyText, String styleText,
			String layoutText, String paletteText, int itemCount, List<String> allowedRoles) {
		return """
				你是面向「%s」平台的%s策划，把用户提供的来源内容拆解为 %d 项视觉计划（策略：%s）。
				视觉风格：%s。画面布局：%s。%s
				规则：
				1. 第 1 项是封面（role=cover）：从内容中提炼最强钩子（悬念/数字/利益点）作主标题。
				2. 其余各项按内容自然段/要点顺序拆分，每项承载一个独立要点，不互相重复、不遗漏关键信息；role 只能是 %s。
				3. 每项 bullets 不超过 5 条，每条不超过 20 字，尽量沿用原文表述。标题与要点会被直接绘制在画面中，务必简短有力。
				4. criticalText 是该卡必须逐字保真的关键文字（数字/价格/店名/事实），每条必须逐字出现在所引来源块中，禁止改写或编造。
				5. illustration 是交给图像生成模型的完整画面描述：依次写清主体及外观细节、场景环境、构图与视角、光线与氛围、质感媒介；100-200 字，与视觉风格一致，不含文字排版要求。
				6. caption 是发布时该项的配文，从对应来源块提炼，口语化；purpose 一句话说明该项目的。
				7. sourceBlockIds 只能从提供的来源块 ID 中选择，且必须真实支撑该项内容。
				只输出 JSON（可包 ```json 代码块）：
				{"items":[{"role":"...","title":"...","bullets":["..."],"criticalText":["..."],"illustration":"...","caption":"...","purpose":"...","sourceBlockIds":["..."]}],"explanation":"..."}
				items 数量必须等于 %d。不要输出任何其他字段。"""
				.formatted(platformLabel, recipeLabel, itemCount, strategyText, styleText, layoutText,
						paletteText == null || paletteText.isBlank() ? "" : "配色基调：" + paletteText + "。",
						String.join(" / ", allowedRoles), itemCount);
	}

	static String user(List<Map<String, Object>> blocks) {
		StringBuilder payload = new StringBuilder("来源内容（块 ID 与文本，sourceBlockIds 只能从中选择）：\n");
		for (Map<String, Object> block : blocks) {
			payload.append("- [").append(block.get("id")).append("]（").append(block.get("kind")).append("）")
					.append(block.get("text")).append('\n');
		}
		return payload.toString();
	}

	// ---- 任务书 #101 C101-14：文章配图／单张封面模板（图卡模板保持上方不变） ----

	/** upstream_commit：文章配图模板版本（本卡新增，与图卡模板独立演进）。 */
	static final String ARTICLE_UPSTREAM_VERSION = "article-visual-prompts-1.0.0";

	/**
	 * article-visuals：封面恰好一张 + 每张正文插图定位到真实段落（afterBlockId）。 answerMode
	 * 为知乎回答：保留问题与开头语义——封面不得复述／改写问题标题。
	 */
	static String articleSystem(String platformLabel, String strategyText, int itemCount, boolean answerMode) {
		return """
				你是面向「%s」的文章配图策划，为这篇稿子规划 %d 张图（策略：%s）。
				规则：
				1. 第 1 项是封面（role=cover）：提炼全文最强的视觉主题；画面干净有辨识度，不罗列正文细节。
				2. 其余各项是正文插图（role=illustration）：每张服务一个具体段落/步骤/数据，不重复、不凑数。
				3. 每张插图必须给出 afterBlockId：该图应插入到来源中哪个块之后（只能从提供的块 ID 中选择）；封面不提供 afterBlockId。
				4. purpose 一句话解释这张图在该位置的作用（解释步骤／放大数据／呈现场景等）。
				5. criticalText 是必须逐字保真的关键文字（数字/结论/名称），每条必须逐字出现在所引来源块中。
				6. illustration 是交给图像生成模型的完整画面描述：主体与细节、场景、构图视角、光线氛围、质感媒介；100-200 字，不含文字排版要求。
				7. caption 留空；title 简短（≤20 字）。bullets 留空数组。
				8. sourceBlockIds 只能从提供的来源块 ID 中选择，且必须真实支撑该图。
				%s只输出 JSON（可包 ```json 代码块）：
				{"items":[{"role":"...","title":"...","bullets":[],"criticalText":["..."],"illustration":"...","caption":"","purpose":"...","sourceBlockIds":["..."],"afterBlockId":"..."}],"explanation":"..."}
				除封面外的项必须带 afterBlockId 字段；封面不得带。items 数量必须等于 %d。不要输出任何其他字段。"""
				.formatted(platformLabel, itemCount, strategyText,
						answerMode ? "9. 这是知乎回答配图：封面与插图都不得复述或改写问题标题，图必须服务回答内容本身（开头语义保持原样）。\n" : "", itemCount);
	}

	/** cover-only：单张封面，无 afterBlockId／无正文插图。 */
	static String coverSystem(String platformLabel, String strategyText, boolean answerMode) {
		return """
				你是面向「%s」的封面策划，为这篇稿子规划恰好 1 张封面图（策略：%s）。
				规则：
				1. 只输出 1 项，role=cover：提炼全文最强的视觉钩子（悬念/数字/利益点）作主标题。
				2. criticalText 必须逐字出现在所引来源块中；sourceBlockIds 只能从提供的块 ID 中选择。
				3. illustration 是交给图像生成模型的完整画面描述：主体与细节、场景、构图视角、光线氛围、质感媒介；100-200 字，不含文字排版要求。
				4. caption 留空；bullets 留空数组；不提供 afterBlockId。
				%s只输出 JSON（可包 ```json 代码块）：
				{"items":[{"role":"cover","title":"...","bullets":[],"criticalText":["..."],"illustration":"...","caption":"","purpose":"...","sourceBlockIds":["..."]}],"explanation":"..."}
				items 数量必须等于 1。不要输出任何其他字段。"""
				.formatted(platformLabel, strategyText, answerMode ? "5. 这是知乎回答封面：不得复述或改写问题标题。\n" : "");
	}

	static String platformLabel(String platform) {
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

	static String strategyText(String strategy) {
		return switch (strategy) {
			case "story" -> "体验叙事——以第一人称经历串联，先钩子后展开";
			case "visual" -> "画面优先——以视觉冲击力组织信息，文字精炼";
			default -> "信息密度优先——要点完整、层次清晰、不牺牲事实";
		};
	}

	/** 新 mint 的身份与模型输出无关（UUIDv4），防模型注入身份字段。 */
	static String mintId() {
		return UUID.randomUUID().toString();
	}
}
