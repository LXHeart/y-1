package com.grassland.intelligence.creationstudio.plan;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务书 #101 C101-05（§6.2/§6.3）：视觉计划行与受控 PlanDocument。
 *
 * <p>
 * document 只承载 §6.2 声明的字段——模型输出经 {@link VisualPlanService} 严格解析后才能 进入
 * document；HTML、工具调用或任意模型字段不落计划。itemId／cardId 是身份（服务端铸造）， position 只是展示顺序。
 */
public final class VisualPlan {

	private VisualPlan() {
	}

	/** 计划行（creation_visual_plan）。 */
	public record PlanRow(UUID id, String ownerAccountId, UUID draftId, String requestId, String requestHash,
			UUID sourceDocumentId, String sourceContentHash, int baseDraftVersion, String baseContentHash,
			String recipeId, String recipeVersion, String upstreamCommit, String inputSnapshotJson,
			String promptCiphertext, String promptHash, String status, int currentRevision, Integer confirmedRevision,
			Integer confirmedDraftVersion, String confirmedContentHash, OffsetDateTime confirmedAt,
			String confirmedActor, UUID runId, String errorCode, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
	}

	/** VisualPlanDocument（§6.2）：共享 style/palette，每项独立 layout。 */
	public record Document(String recipeId, String recipeVersion, String strategy, Style style, List<Item> items,
			String explanation, List<String> uncoveredBlockIds) {

		public Map<String, Object> toMap() {
			return Map.ofEntries(Map.entry("recipe", Map.of("id", recipeId, "version", recipeVersion)),
					Map.entry("strategy", strategy), Map.entry("style", style.toMap()),
					Map.entry("items", items.stream().map(Item::toMap).toList()),
					Map.entry("explanation", explanation == null ? "" : explanation),
					Map.entry("uncoveredBlockIds", uncoveredBlockIds));
		}
	}

	/** VisualStyle（§6.2）：三段 ID 引用预设目录，具体含义由契约渲染。 */
	public record Style(String styleId, String layoutId, String paletteId) {

		public Map<String, Object> toMap() {
			return Map.of("styleId", styleId, "layoutId", layoutId, "paletteId", paletteId);
		}
	}

	/** VisualPlanItem（§6.2）；placement 仅文章配图使用（本卡模板不产生）。 */
	public record Item(String itemId, String cardId, int position, String role, String title, List<String> bullets,
			String caption, String purpose, String illustration, List<String> sourceBlockIds, List<String> criticalText,
			String layoutId, String targetAspect, String placementAfterBlockId) {

		public Map<String, Object> toMap() {
			// placement 允许 null（Map.entry 不接受 null 值，用 LinkedHashMap 承载）
			Map<String, Object> map = new java.util.LinkedHashMap<>();
			map.put("itemId", itemId);
			map.put("cardId", cardId);
			map.put("position", position);
			map.put("role", role);
			map.put("title", title);
			map.put("bullets", bullets);
			map.put("caption", caption == null ? "" : caption);
			map.put("purpose", purpose == null ? "" : purpose);
			map.put("illustration", illustration);
			map.put("sourceBlockIds", sourceBlockIds);
			map.put("criticalText", criticalText);
			map.put("layoutId", layoutId);
			map.put("targetAspect", targetAspect);
			map.put("placement", placementAfterBlockId == null ? null : Map.of("afterBlockId", placementAfterBlockId));
			return map;
		}
	}
}
