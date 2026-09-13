package com.grassland.intelligence.creationcanvas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Bounded, deterministic context: explicit selection first, then one-hop
 * references ordered by node ID.
 */
@org.springframework.stereotype.Component
public class CanvasAgentContextBuilder {
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final int MAX_SELECTED = 20;
	public static final int MAX_CONTEXT_NODES = 40;
	public static final int PER_NODE_SUMMARY_CP = 2000;
	public static final int TOTAL_SUMMARY_CP = 24000;
	public static final int OUTPUT_MAX_TOKENS = 4096;
	private static final String TRUNCATED = "…（截断）";
	private final CanvasReferenceAccess references;
	public CanvasAgentContextBuilder(CanvasReferenceAccess references) {
		this.references = references;
	}
	public record Context(String clarify, String contextJson, List<String> selectedShotIds) {
	}
	private record Graph(Map<String, JsonNode> nodes, List<String> ids, int omitted) {
	}

	public Mono<Context> buildAuthorized(String accountId, String draftId, String storyboardId, List<String> selected,
			String document, List<Map<String, Object>> shots, String constraints) {
		if (selected == null || selected.size() > MAX_SELECTED || new HashSet<>(selected).size() != selected.size())
			return Mono.error(limit("最多选择 20 个不重复节点"));
		if (selected.isEmpty())
			return Mono.just(new Context("请先在画布选中要修改的具体镜头，再提出修改要求。", null, List.of()));
		Graph graph = graph(document, storyboardId, selected);
		Map<String, Map<String, Object>> media = new LinkedHashMap<>();
		return Flux.fromIterable(graph.ids()).concatMap(id -> {
			JsonNode node = graph.nodes().get(id);
			if (!"media".equals(node.path("kind").asText()))
				return Mono.empty();
			return references.require(accountId, node.path("refType").asText(), node.path("refId").asText())
					.doOnNext(metadata -> media.put(id, metadata));
		}).then(Mono.fromSupplier(() -> summarize(graph, selected, shots, media, constraints)));
	}

	private Graph graph(String document, String storyboardId, List<String> selected) {
		try {
			JsonNode root = MAPPER.readTree(document);
			if (!storyboardId.equals(root.path("storyboardId").asText()) || !root.path("nodes").isArray())
				throw invalid("画布身份或节点无效");
			Map<String, JsonNode> nodes = new LinkedHashMap<>();
			for (JsonNode node : root.path("nodes")) {
				if (!node.path("id").isTextual() || nodes.put(node.path("id").asText(), node) != null)
					throw invalid("画布节点重复或无效");
			}
			if (!nodes.keySet().containsAll(selected))
				throw invalid("选择包含当前画布不存在的节点");
			Set<String> neighbors = new java.util.TreeSet<>();
			for (JsonNode edge : root.path("edges")) {
				if (!"reference".equals(edge.path("kind").asText()))
					continue;
				String from = edge.path("fromNodeId").asText(), to = edge.path("toNodeId").asText();
				if (!nodes.containsKey(from) || !nodes.containsKey(to))
					throw invalid("参考线端点不可用");
				if (selected.contains(from))
					neighbors.add(to);
				if (selected.contains(to))
					neighbors.add(from);
			}
			neighbors.removeAll(selected);
			List<String> ids = new ArrayList<>(selected);
			ids.addAll(neighbors.stream().limit(MAX_CONTEXT_NODES - selected.size()).toList());
			return new Graph(nodes, ids, Math.max(0, neighbors.size() - (MAX_CONTEXT_NODES - selected.size())));
		} catch (IntelligenceException error) {
			throw error;
		} catch (Exception error) {
			throw invalid("画布文档不可读");
		}
	}

	private Context summarize(Graph graph, List<String> selected, List<Map<String, Object>> shots,
			Map<String, Map<String, Object>> media, String constraints) {
		Map<String, Map<String, Object>> byShot = new LinkedHashMap<>();
		for (var shot : shots)
			byShot.put((String) shot.get("id"), shot);
		List<String> selectedShots = new ArrayList<>();
		StringBuilder context = new StringBuilder(clip(constraints == null ? "" : constraints, 4000));
		context.append('\n');
		for (String id : graph.ids()) {
			JsonNode node = graph.nodes().get(id);
			String kind = node.path("kind").asText();
			String ref = node.path("refId").asText();
			boolean explicit = selected.contains(id);
			String summary;
			switch (kind) {
				case "shot" -> {
					if (!id.equals("shot:" + ref) || !byShot.containsKey(ref))
						throw invalid("选择的镜头已不存在");
					if (explicit) {
						selectedShots.add(ref);
						summary = "kind=shot; " + json(byShot.get(ref));
					} else
						summary = "kind=shot; shotId=" + ref + "（未选中，不包含正文）";
				}
				case "media" -> summary = "kind=media; refId=" + ref + "; " + json(media.get(id)) + "（仅元数据，未做画面分析）";
				case "note" -> summary = "kind=note; text=" + node.path("text").asText();
				case "brief", "delivery" -> summary = "kind=" + kind + "; label=" + node.path("label").asText("");
				case "take" -> summary = "kind=take; takeId=" + ref;
				default -> throw invalid("选中节点类型不支持");
			}
			String line = clip((explicit ? "[选中] " : "[引用] ") + id + ": " + summary, PER_NODE_SUMMARY_CP - 1) + "\n";
			if (points(context.toString()) + points(line) > TOTAL_SUMMARY_CP - 30) {
				if (explicit)
					throw limit("选中节点上下文超过 24000 字符预算，请减少选择");
				context.append("（其余引用因上下文预算省略）");
				break;
			}
			context.append(line);
		}
		if (graph.omitted() > 0)
			context.append("（其余引用因节点上限省略）");
		return new Context(null, context.toString(), List.copyOf(selectedShots));
	}

	static String clip(String value, int max) {
		if (points(value) <= max)
			return value;
		int kept = Math.max(0, max - points(TRUNCATED));
		return value.substring(0, value.offsetByCodePoints(0, kept)) + TRUNCATED;
	}
	private static int points(String value) {
		return value.codePointCount(0, value.length());
	}
	private static String json(Object value) {
		try {
			return MAPPER.writeValueAsString(value);
		} catch (Exception error) {
			throw invalid("上下文无法序列化");
		}
	}
	private static IntelligenceException invalid(String message) {
		return new IntelligenceException(400, "CANVAS_INVALID_INPUT", message);
	}
	private static IntelligenceException limit(String message) {
		return new IntelligenceException(400, "CANVAS_LIMIT_EXCEEDED", message);
	}
}
