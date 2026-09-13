package com.grassland.intelligence.creationstudio.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.grassland.intelligence.creationstudio.CreationRecipeCatalog.RecipeDefinition;
import java.util.Set;

/**
 * 任务书 #101 C101-14：文章配图与单张封面的 recipe 分派适配器。
 *
 * <p>
 * 职责：按 recipe 决定 prompt 形态（文章／封面模板见 {@link VisualPlanPrompts}，图卡回归
 * 不变）与模型输出字段语义——article-visuals 的正文插图必须携带 afterBlockId（定位真实
 * 段落），封面不得携带；cover-only 恰好一张封面、无段落定位。知乎 answer 模式保留问题 与开头语义（prompt
 * 明令禁止复述/改写问题标题）。段落位置随采用持久化由 VisualAdoptionService 在同一采用事务写入。
 */
final class ArticleVisualPlanAdapter {

	private ArticleVisualPlanAdapter() {
	}

	static boolean isArticleVisuals(String recipeId) {
		return "article-visuals".equals(recipeId);
	}

	static boolean isCoverOnly(String recipeId) {
		return "cover-only".equals(recipeId);
	}

	/** 文章配图／单张封面模板的 system prompt（图卡模板不走这里）。 */
	static String systemPrompt(RecipeDefinition recipe, String platformLabel, String strategyText, int itemCount,
			boolean answerMode) {
		if (isArticleVisuals(recipe.id())) {
			return VisualPlanPrompts.articleSystem(platformLabel, strategyText, itemCount, answerMode);
		}
		return VisualPlanPrompts.coverSystem(platformLabel, strategyText, answerMode);
	}

	/** 该 recipe 的模型项允许输出的字段（服务端职责字段一律不允许）。 */
	static Set<String> modelItemFields(String recipeId) {
		return isArticleVisuals(recipeId)
				? Set.of("role", "title", "bullets", "criticalText", "illustration", "caption", "purpose",
						"sourceBlockIds", "afterBlockId")
				: Set.of("role", "title", "bullets", "criticalText", "illustration", "caption", "purpose",
						"sourceBlockIds");
	}

	/**
	 * 解析 afterBlockId：article-visuals 的非封面项必填且必须属于本次选择；封面与其他 recipe 不允许携带。返回 null
	 * 表示无段落定位。
	 */
	static String parseAfterBlockId(String recipeId, String role, JsonNode node, Set<String> selectedIds) {
		boolean hasField = node.hasNonNull("afterBlockId");
		if (!isArticleVisuals(recipeId)) {
			if (hasField) {
				throw new IllegalArgumentException("该模板不允许 afterBlockId");
			}
			return null;
		}
		if ("cover".equals(role)) {
			if (hasField) {
				throw new IllegalArgumentException("封面不得携带 afterBlockId");
			}
			return null;
		}
		if (!hasField) {
			throw new IllegalArgumentException("正文插图必须提供 afterBlockId");
		}
		String afterBlockId = node.path("afterBlockId").asText();
		if (!selectedIds.contains(afterBlockId)) {
			throw new IllegalArgumentException("afterBlockId 不在本次选择的来源块内");
		}
		return afterBlockId;
	}
}
