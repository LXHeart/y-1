package com.grassland.intelligence.creationcanvas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 独立画布文档（任务书 #100 C100-09 / API-08/09 / §6.1 CanvasDocumentBody）。
 *
 * <p>纯结构校验层：schema、限额（256KiB / 200 节点 / 500 边）、节点 ID 与 kind↔refType 配对、
 * canonical ID 一致性、边方向/悬空/自环/重复端点/环。跨项目 canonical 引用（shot/take 指向
 * 其他分镜）需要读库，由 {@link CreationCanvasController} 在结构校验后追加；已不存在的引用
 * 是合法历史（§7.3「历史无效引用保留位置和不可用状态」），本层不查存在性。
 *
 * <p>服务本地 Jackson 实例（intelligence 无共享 ObjectMapper bean 惯例）；未知字段一律拒绝
 * （§6.1「未知字段拒绝」）。
 */
public final class CreationCanvasDocument {

	public static final int MAX_NODES = 200;
	public static final int MAX_EDGES = 500;
	public static final int MAX_BYTES = 256 * 1024;
	/** 坐标/视口钳制口径与前端 useCanvasViewport 一致（§8.3）。 */
	public static final double POSITION_LIMIT = 100_000d;
	public static final double SCALE_MIN = 0.25d;
	public static final double SCALE_MAX = 2.5d;
	private static final Pattern NODE_ID = Pattern.compile("^[A-Za-z0-9:_-]{1,96}$");
	private static final Pattern UUID = Pattern.compile(
			"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final Set<String> NODE_FIELDS = Set.of("id", "kind", "refType", "refId", "label", "text", "x", "y");
	private static final Set<String> EDGE_FIELDS = Set.of("id", "kind", "fromNodeId", "toNodeId");
	private static final Set<String> BODY_FIELDS = Set.of("schemaVersion", "storyboardId", "viewport", "nodes",
			"edges", "activeBranchId");
	private static final Set<String> VIEWPORT_FIELDS = Set.of("panX", "panY", "scale");

	private CreationCanvasDocument() {
	}

	/** 结构校验入口：返回原样 document 文本（幂等回存），非法抛 400；canonical 引用收集到 refs。 */
	public static String validateBody(String documentJson, String draftId, java.util.List<String[]> canonicalRefs) {
		JsonNode root = parseStrict(documentJson);
		requireFields(root, BODY_FIELDS, "document");
		if (!root.path("schemaVersion").isInt() || root.path("schemaVersion").asInt() != 1) {
			throw invalid("schemaVersion 必须为 1");
		}
		String storyboardId = requireUuid(root.path("storyboardId"), "storyboardId");
		// 256KiB 以规范序列化口径衡量（与库内 jsonb 再序列化一致），原文另在入口粗检
		try {
			byte[] canonical = MAPPER.writeValueAsBytes(MAPPER.readTree(documentJson));
			if (canonical.length > MAX_BYTES) {
				throw new IntelligenceException(400, "CANVAS_LIMIT_EXCEEDED",
						"画布文档超过 256KiB 上限（" + canonical.length + " 字节）");
			}
		} catch (IntelligenceException e) {
			throw e;
		} catch (Exception e) {
			throw invalid("document 不是合法 JSON");
		}

		JsonNode viewport = root.path("viewport");
		requireFields(viewport, VIEWPORT_FIELDS, "viewport");
		double scale = requireNumber(viewport.path("scale"), "viewport.scale");
		if (scale < SCALE_MIN || scale > SCALE_MAX) {
			throw invalid("viewport.scale 必须在 " + SCALE_MIN + "～" + SCALE_MAX);
		}
		requireNumber(viewport.path("panX"), "viewport.panX");
		requireNumber(viewport.path("panY"), "viewport.panY");

		JsonNode activeBranch = root.path("activeBranchId");
		if (!activeBranch.isNull() && !isUuidText(activeBranch)) {
			throw invalid("activeBranchId 必须是 UUID 或 null");
		}

		JsonNode nodes = root.path("nodes");
		if (!nodes.isArray()) {
			throw invalid("nodes 必须是数组");
		}
		if (nodes.size() > MAX_NODES) {
			throw new IntelligenceException(400, "CANVAS_LIMIT_EXCEEDED", "节点数超过上限 " + MAX_NODES);
		}
		Map<String, String> nodeIdKinds = new LinkedHashMap<>();
		for (JsonNode node : nodes) {
			validateNode(node, storyboardId, draftId, canonicalRefs, nodeIdKinds);
		}

		JsonNode edges = root.path("edges");
		if (!edges.isArray()) {
			throw invalid("edges 必须是数组");
		}
		if (edges.size() > MAX_EDGES) {
			throw new IntelligenceException(400, "CANVAS_LIMIT_EXCEEDED", "边数超过上限 " + MAX_EDGES);
		}
		validateEdges(edges, nodeIdKinds);
		return documentJson;
	}

	private static void validateNode(JsonNode node, String storyboardId, String draftId,
			java.util.List<String[]> canonicalRefs, Map<String, String> nodeIdKinds) {
		requireFields(node, NODE_FIELDS, "node");
		String id = requireText(node.path("id"), "node.id");
		if (!NODE_ID.matcher(id).matches()) {
			throw invalid("node.id 字符集或长度非法（1～96，[A-Za-z0-9:_-]）");
		}
		if (nodeIdKinds.containsKey(id)) {
			throw invalid("node.id 重复：" + id);
		}
		String kind = requireKind(node.path("kind"), "brief", "media", "shot", "take", "delivery", "note");
		String refType = requireKind(node.path("refType"), "draft", "media", "content-asset", "shot", "take", "note");
		// kind ↔ refType 配对（§6.3）：只有下表允许
		boolean pairOk = switch (kind) {
			case "brief", "delivery" -> refType.equals("draft");
			case "shot" -> refType.equals("shot");
			case "take" -> refType.equals("take");
			case "media" -> refType.equals("media") || refType.equals("content-asset");
			case "note" -> refType.equals("note");
			default -> false;
		};
		if (!pairOk) {
			throw invalid("kind=" + kind + " 不允许 refType=" + refType);
		}
		JsonNode refId = node.path("refId");
		switch (kind) {
			case "brief" -> requireCanonical(node, id, "brief", draftId);
			case "delivery" -> requireCanonical(node, id, "delivery", draftId);
			case "shot", "take" -> {
				if (!refId.isTextual() || !UUID.matcher(refId.asText()).matches()) {
					throw invalid(kind + " 节点 refId 必须是 UUID");
				}
				if (!id.equals(kind + ":" + refId.asText())) {
					throw invalid("canonical id 必须为 " + kind + ":{refId}");
				}
				// 跨项目防线在控制器经仓储批量探测（引用存在但归属其他分镜 → 拒绝；
				// 不存在 = 合法历史引用，§7.3）
				canonicalRefs.add(new String[] { kind, refId.asText() });
			}
			case "media" -> {
				if (!refId.isTextual() || !UUID.matcher(refId.asText()).matches()) {
					throw invalid("media 节点 refId 必须是 UUID");
				}
				if (!id.equals("media:" + refId.asText())) {
					throw invalid("canonical id 必须为 media:{refId}");
				}
			}
			case "note" -> {
				if (!refId.isNull()) {
					throw invalid("note 节点 refId 必须为 null");
				}
				if (!node.path("text").isTextual()) {
					throw invalid("note 节点 text 必须是字符串正文");
				}
				if (node.path("text").asText().codePoints().count() > 4000) {
					throw new IntelligenceException(400, "CANVAS_LIMIT_EXCEEDED", "note.text 超过 4000 字符");
				}
			}
			default -> {
			}
		}
		if (!"note".equals(kind) && !node.path("text").isNull()) {
			throw invalid("非 note 节点 text 必须为 null");
		}
		JsonNode label = node.path("label");
		if (!label.isNull() && (!label.isTextual() || label.asText().codePoints().count() > 400)) {
			throw invalid("label 必须为 null 或 ≤400 字符");
		}
		double x = requireNumber(node.path("x"), "node.x");
		double y = requireNumber(node.path("y"), "node.y");
		if (Math.abs(x) > POSITION_LIMIT || Math.abs(y) > POSITION_LIMIT) {
			throw invalid("节点坐标超出 ±" + POSITION_LIMIT);
		}
		nodeIdKinds.put(id, kind);
	}

	private static void validateEdges(JsonNode edges, Map<String, String> nodeIdKinds) {
		Set<String> edgeIds = new HashSet<>();
		// 有向图（环检测）+ 无序端点对（重复端点拒绝）
		Map<String, java.util.List<String>> adjacency = new HashMap<>();
		Set<String> endpointPairs = new HashSet<>();
		for (JsonNode edge : edges) {
			requireFields(edge, EDGE_FIELDS, "edge");
			String id = requireText(edge.path("id"), "edge.id");
			if (!NODE_ID.matcher(id).matches()) {
				throw invalid("edge.id 字符集或长度非法");
			}
			if (!edgeIds.add(id)) {
				throw invalid("edge.id 重复：" + id);
			}
			if (!"reference".equals(edge.path("kind").asText(null))) {
				throw invalid("edge.kind 只允许 reference");
			}
			String from = requireText(edge.path("fromNodeId"), "edge.fromNodeId");
			String to = requireText(edge.path("toNodeId"), "edge.toNodeId");
			String fromKind = nodeIdKinds.get(from);
			String toKind = nodeIdKinds.get(to);
			if (fromKind == null || toKind == null) {
				throw invalid("边端点必须是文档内节点（禁止悬空）");
			}
			if (from.equals(to)) {
				throw invalid("禁止自环边");
			}
			// 方向（§6.3）：brief/media/note → shot；note → media
			boolean directionOk = ("shot".equals(toKind) && ("brief".equals(fromKind) || "media".equals(fromKind)
					|| "note".equals(fromKind)))
					|| ("media".equals(toKind) && "note".equals(fromKind));
			if (!directionOk) {
				throw invalid("参考线方向只允许 brief/media/note→shot 或 note→media");
			}
			String pairKey = from.compareTo(to) < 0 ? from + "|" + to : to + "|" + from;
			if (!endpointPairs.add(pairKey)) {
				throw invalid("重复端点边：" + pairKey.replace('|', '→'));
			}
			adjacency.computeIfAbsent(from, key -> new java.util.ArrayList<>()).add(to);
		}
		assertNoCycle(adjacency);
	}

	private static void assertNoCycle(Map<String, java.util.List<String>> adjacency) {
		Map<String, Integer> state = new HashMap<>(); // 0=未访 1=在栈 2=完成
		Deque<String> stack = new ArrayDeque<>();
		for (String start : adjacency.keySet()) {
			if (state.getOrDefault(start, 0) != 0) {
				continue;
			}
			stack.push(start);
			while (!stack.isEmpty()) {
				String current = stack.peek();
				int cs = state.getOrDefault(current, 0);
				if (cs == 0) {
					state.put(current, 1);
					for (String next : adjacency.getOrDefault(current, java.util.List.of())) {
						int ns = state.getOrDefault(next, 0);
						if (ns == 1) {
							throw invalid("参考线存在环：" + next);
						}
						if (ns == 0) {
							stack.push(next);
						}
					}
				} else if (cs == 1) {
					state.put(current, 2);
					stack.pop();
				} else {
					stack.pop();
				}
			}
		}
	}

	private static void requireCanonical(JsonNode node, String id, String prefix, String draftId) {
		if (!id.equals(prefix + ":" + draftId)) {
			throw invalid("canonical id 必须为 " + prefix + ":{当前草稿}");
		}
		if (!node.path("refId").isTextual() || !draftId.equals(node.path("refId").asText())) {
			throw invalid(prefix + " 节点 refId 必须指向当前草稿");
		}
	}

	private static JsonNode parseStrict(String json) {
		try {
			return MAPPER.readTree(json);
		} catch (Exception e) {
			throw invalid("document 不是合法 JSON");
		}
	}

	private static void requireFields(JsonNode node, Set<String> allowed, String where) {
		if (!node.isObject()) {
			throw invalid(where + " 必须是对象");
		}
		Set<String> seen = new HashSet<>();
		node.fieldNames().forEachRemaining(seen::add);
		for (String field : seen) {
			if (!allowed.contains(field)) {
				throw invalid(where + " 含未知字段：" + field);
			}
		}
		for (String required : allowed) {
			// label/text/refId/activeBranchId 允许 null 显式出现；其余必填
			if (!seen.contains(required) && !"label".equals(required) && !"text".equals(required)
					&& !"refId".equals(required) && !"activeBranchId".equals(required)) {
				throw invalid(where + " 缺字段：" + required);
			}
		}
	}

	private static String requireText(JsonNode node, String field) {
		if (!node.isTextual() || node.asText().isBlank()) {
			throw invalid(field + " 必须是非空字符串");
		}
		return node.asText();
	}

	private static String requireUuid(JsonNode node, String field) {
		String text = requireText(node, field);
		if (!UUID.matcher(text).matches()) {
			throw invalid(field + " 必须是 UUID");
		}
		return text;
	}

	private static double requireNumber(JsonNode node, String field) {
		if (!node.isNumber() || !node.isDouble() && !node.isInt() && !node.isLong()) {
			throw invalid(field + " 必须是数字");
		}
		return node.asDouble();
	}

	private static boolean isUuidText(JsonNode node) {
		return node.isTextual() && UUID.matcher(node.asText()).matches();
	}

	private static String requireKind(JsonNode node, String... allowed) {
		String value = node.asText(null);
		for (String candidate : allowed) {
			if (candidate.equals(value)) {
				return value;
			}
		}
		throw invalid("kind/refType 枚举非法：" + value);
	}

	private static IntelligenceException invalid(String message) {
		return new IntelligenceException(400, "CANVAS_INVALID_INPUT", message);
	}

}
