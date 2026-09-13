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
