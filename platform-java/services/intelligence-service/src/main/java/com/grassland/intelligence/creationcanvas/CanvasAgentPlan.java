package com.grassland.intelligence.creationcanvas;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.grassland.intelligence.videoproduction.VideoStoryboardEditService;
import java.util.HashSet;
import java.util.Set;

/**
 * One public kind-discriminated protocol. Historical wrappers are decoded only
 * at the storage boundary.
 */
public final class CanvasAgentPlan {
	private static final ObjectMapper MAPPER = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
	public static final int MAX_EDIT_ACTIONS = 12;
	public static final int MAX_SHOTS_PER_STORYBOARD = 30;
	private static final Set<String> CONTENT = Set.of("visual", "narration", "plannedSeconds", "cameraMove",
			"anchorImageIndex");
	private CanvasAgentPlan() {
	}

	public static String parseAction(String modelOutput) {
		JsonNode action = read(modelOutput);
		if (!action.isObject() || !action.path("kind").isTextual())
			throw invalid("action.kind 必填");
		switch (action.path("kind").asText()) {
			case "edit" -> {
				fields(action, Set.of("kind", "actions"), Set.of());
				var actions = action.path("actions");
				if (!actions.isArray() || actions.isEmpty() || actions.size() > MAX_EDIT_ACTIONS)
					throw invalid("edit.actions 须为 1～12 项");
				Set<String> updated = new HashSet<>();
				for (JsonNode item : actions) {
					if ("update-shot".equals(item.path("kind").asText())) {
						fields(item, Set.of("kind", "patch"), Set.of());
						var patch = item.path("patch");
						fields(patch, Set.of("shotId"), CONTENT);
						uuid(patch.path("shotId"));
						if (!updated.add(patch.path("shotId").asText()))
							throw invalid("同一镜头不可重复修改");
						content(patch);
					} else if ("append-shot".equals(item.path("kind").asText())) {
						fields(item, Set.of("kind", "shot"), Set.of());
						fields(item.path("shot"), CONTENT, Set.of());
						content(item.path("shot"));
					} else
						throw invalid("未知编辑动作");
				}
			}
			case "variant" -> {
				fields(action, Set.of("kind", "title", "shotIds"), Set.of());
				text(action.path("title"), 1, 60, "title");
				var ids = action.path("shotIds");
				if (!ids.isArray() || ids.isEmpty() || ids.size() > 30)
					throw invalid("shotIds 须为 1～30 项");
				Set<String> seen = new HashSet<>();
				for (JsonNode id : ids) {
					uuid(id);
					if (!seen.add(id.asText()))
						throw invalid("shotIds 重复");
				}
			}
			case "prepare-generation" -> {
				fields(action, Set.of("kind", "mode", "shotId"), Set.of());
				var mode = action.path("mode");
				if (!mode.isTextual() || !Set.of("initial", "regenerate", "reroll").contains(mode.asText()))
					throw invalid("mode 非法");
				if ("initial".equals(mode.asText())) {
					if (!action.path("shotId").isNull())
						throw invalid("initial 的 shotId 必须为 null");
				} else
					uuid(action.path("shotId"));
			}
			default -> throw invalid("未知动作 kind");
		}
		return action.toString();
	}

	public static String decodeStoredAction(String stored) {
		JsonNode root = read(stored);
		if (root.has("kind"))
			return parseAction(stored);
		if (!root.isObject() || root.size() != 1)
			throw invalid("历史动作不明确");
		String kind = root.fieldNames().next();
		JsonNode value = root.get(kind);
		if (!value.isObject() || value.has("kind"))
			throw invalid("历史动作形状混合");
		ObjectNode action = MAPPER.createObjectNode().put("kind", kind);
		value.fields().forEachRemaining(field -> action.set(field.getKey(), field.getValue()));
		return parseAction(action.toString());
	}

	public static void validateAgainstContext(String actionJson, Set<String> selectedShotIds, int imageCount,
			int shotCount) {
		JsonNode action = read(parseAction(actionJson));
		switch (action.path("kind").asText()) {
			case "edit" -> {
				int appended = 0;
				for (JsonNode item : action.path("actions")) {
					JsonNode fields;
					if ("update-shot".equals(item.path("kind").asText())) {
						fields = item.path("patch");
						selected(fields.path("shotId").asText(), selectedShotIds);
					} else {
						fields = item.path("shot");
						appended++;
					}
					if (fields.has("anchorImageIndex") && fields.path("anchorImageIndex").asInt() > imageCount)
						throw invalid("锚图索引超过实际图片数量");
				}
				if (shotCount + appended > MAX_SHOTS_PER_STORYBOARD)
					throw invalid("追加后超过 30 镜");
			}
			case "variant" -> {
				for (JsonNode id : action.path("shotIds"))
					selected(id.asText(), selectedShotIds);
			}
			case "prepare-generation" -> {
				if (!action.path("shotId").isNull())
					selected(action.path("shotId").asText(), selectedShotIds);
			}
			default -> throw invalid("未知动作");
		}
	}

	public static void validateUpdateScope(String actionJson, Set<String> selectedShotIds) {
		validateAgainstContext(decodeStoredAction(actionJson), selectedShotIds, Integer.MAX_VALUE, 0);
	}

	private static void selected(String id, Set<String> ids) {
		if (!ids.contains(id))
			throw invalid("动作指向未选中的镜头");
	}
	private static void content(JsonNode value) {
		if (CONTENT.stream().noneMatch(value::has))
			throw invalid("patch 至少包含一个内容字段");
		if (value.has("visual"))
			text(value.path("visual"), 1, 4000, "visual");
		if (value.has("narration"))
			text(value.path("narration"), 0, 4000, "narration");
		if (value.has("plannedSeconds"))
			integer(value.path("plannedSeconds"), 4, 6, "plannedSeconds");
		if (value.has("anchorImageIndex"))
			integer(value.path("anchorImageIndex"), 0, Integer.MAX_VALUE, "anchorImageIndex");
		if (value.has("cameraMove") && (!value.path("cameraMove").isTextual()
				|| !VideoStoryboardEditService.CAMERA_MOVES.contains(value.path("cameraMove").asText())))
			throw invalid("cameraMove 非法");
	}
	private static void fields(JsonNode node, Set<String> required, Set<String> optional) {
		if (!node.isObject())
			throw invalid("字段必须为对象");
		if (required.stream().anyMatch(field -> !node.has(field)))
			throw invalid("缺少必填字段");
		node.fieldNames().forEachRemaining(field -> {
			if (!required.contains(field) && !optional.contains(field))
				throw invalid("未知字段 " + field);
		});
	}
	private static void text(JsonNode node, int min, int max, String name) {
		if (!node.isTextual())
			throw invalid(name + " 必须为字符串");
		int length = node.asText().trim().codePointCount(0, node.asText().trim().length());
		if (length < min || length > max)
			throw invalid(name + " 长度越界");
	}
	private static void integer(JsonNode node, int min, int max, String name) {
		if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < min || node.intValue() > max)
			throw invalid(name + " 必须为范围内整数");
	}
	private static void uuid(JsonNode node) {
		if (!node.isTextual() || !node.asText()
				.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
			throw invalid("镜头 ID 必须为 UUID");
	}
	private static JsonNode read(String json) {
		try {
			JsonNode value = MAPPER.readTree(json);
			if (value == null)
				throw invalid("JSON 为空");
			return value;
		} catch (Exception error) {
			throw invalid("计划不是合法 JSON");
		}
	}
	private static IllegalArgumentException invalid(String message) {
		return new IllegalArgumentException(message);
	}
}
