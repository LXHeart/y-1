package com.grassland.intelligence.creationcanvas;

import com.grassland.intelligence.security.IntelligenceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 服务端选中上下文构建（任务书 #100 C100-16 / §6.6）。
 *
 * <p>只从服务端已授权的当前草稿/画布/选中节点及一跳引用构建：最多 20 选中节点、40 上下文
 * 节点、每节点摘要 2000 code points、总摘要 24000 code points、输出 4096 tokens。空选择
 * 返回 clarify（不默认改全项目）；选中节点本身超总预算 400 拒绝（不静默丢目标）。媒体只给
 * 已核实元数据，不带签名 URL/密钥/无关项目正文。
 */
@org.springframework.stereotype.Component
public class CanvasAgentContextBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final int MAX_SELECTED = 20;
    public static final int MAX_CONTEXT_NODES = 40;
    public static final int PER_NODE_SUMMARY_CP = 2000;
    public static final int TOTAL_SUMMARY_CP = 24000;
    public static final int OUTPUT_MAX_TOKENS = 4096;

    /** 上下文构建结果：clarify 非空 = 空选择固定引导；contextJson = 发给模型的全部可见范围。 */
    public record Context(String clarify, String contextJson, List<String> selectedShotIds) {
    }

    public Context build(String accountId, String draftId, String storyboardId,
            List<String> selectedNodeIds, String canvasDocumentJson, List<Map<String, Object>> shots) {
        if (selectedNodeIds == null || selectedNodeIds.isEmpty()) {
            return new Context("请先在画布选中要修改的具体镜头，再提出修改要求（不会默认改全项目）。",
                    null, List.of());
        }
        if (selectedNodeIds.size() > MAX_SELECTED) {
            throw new IntelligenceException(400, "CANVAS_LIMIT_EXCEEDED",
                    "选中节点超过上限 " + MAX_SELECTED);
        }
        // 画布节点（含选中与一跳引用），服务端权威读取——不信任浏览器发来的节点内容
        Map<String, Map<String, Object>> nodesById = new LinkedHashMap<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        try {
            Map<String, Object> document = MAPPER.readValue(canvasDocumentJson == null ? "{}"
                    : canvasDocumentJson, new com.fasterxml.jackson.core.type.TypeReference<
                            Map<String, Object>>() {
                    });
            if (document.get("nodes") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> node && node.get("id") instanceof String id) {
                        Map<String, Object> typed = new LinkedHashMap<>();
                        node.forEach((key, value) -> typed.put(String.valueOf(key), value));
                        nodesById.put(id, typed);
                    }
                }
            }
            if (document.get("edges") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> edge) {
                        Map<String, Object> typed = new LinkedHashMap<>();
                        edge.forEach((key, value) -> typed.put(String.valueOf(key), value));
                        edges.add(typed);
                    }
                }
            }
        } catch (Exception e) {
            throw new IntelligenceException(400, "CANVAS_INVALID_INPUT", "画布文档不可读");
        }
        // 镜头权威内容（storyboard 服务端读取；selectedShotIds 以选中顺序为准）
        Map<String, Map<String, Object>> shotById = new LinkedHashMap<>();
        for (Map<String, Object> shot : shots) {
            if (shot.get("id") instanceof String id) {
                shotById.put(id, shot);
            }
        }
        Set<String> contextIds = new LinkedHashSet<>(selectedNodeIds);
        for (Map<String, Object> edge : edges) {
            Object from = edge.get("fromNodeId");
            Object to = edge.get("toNodeId");
            if (selectedNodeIds.contains(from) && to instanceof String toId) {
                contextIds.add(toId);
            } else if (selectedNodeIds.contains(to) && from instanceof String fromId) {
                contextIds.add(fromId);
            }
            if (contextIds.size() >= MAX_CONTEXT_NODES) {
                break;
            }
        }
        if (contextIds.size() > MAX_CONTEXT_NODES) {
            contextIds = new LinkedHashSet<>(new ArrayList<>(contextIds).subList(0, MAX_CONTEXT_NODES));
        }
        List<String> selectedShotIds = new ArrayList<>();
        for (String nodeId : selectedNodeIds) {
            if (nodeId.startsWith("shot:") && shotById.containsKey(nodeId.substring(5))) {
                selectedShotIds.add(nodeId.substring(5));
            }
        }
        // 节点摘要（选中优先；镜头内容取服务端行；媒体只有元数据）
        StringBuilder context = new StringBuilder();
        int totalCp = 0;
        for (String nodeId : contextIds) {
            Map<String, Object> node = nodesById.get(nodeId);
            String summary = summarizeNode(nodeId, node, shotById);
            int nodeCp = summary.codePointCount(0, summary.length());
            boolean isSelected = selectedNodeIds.contains(nodeId);
            if (isSelected && totalCp + Math.min(nodeCp, PER_NODE_SUMMARY_CP) > TOTAL_SUMMARY_CP) {
                throw new IntelligenceException(400, "CANVAS_LIMIT_EXCEEDED",
                        "选中节点上下文超过总预算（" + TOTAL_SUMMARY_CP + " 字符）");
            }
            if (totalCp + Math.min(nodeCp, PER_NODE_SUMMARY_CP) > TOTAL_SUMMARY_CP) {
                break;
            }
            String truncated = nodeCp > PER_NODE_SUMMARY_CP
                    ? summary.substring(0, summary.offsetByCodePoints(0, PER_NODE_SUMMARY_CP)) + "…（截断）"
                    : summary;
            totalCp += truncated.codePointCount(0, truncated.length());
            context.append(isSelected ? "[选中] " : "[引用] ").append(nodeId).append(": ")
                    .append(truncated).append('\n');
        }
        return new Context(null, context.toString(), selectedShotIds);
    }

    /** 节点摘要：镜头用服务端内容；媒体/note 只元数据/文本；不包含签名 URL。 */
    private static String summarizeNode(String nodeId, Map<String, Object> node,
            Map<String, Map<String, Object>> shotById) {
        String kind = node == null ? "unknown" : String.valueOf(node.get("kind"));
        if (nodeId.startsWith("shot:")) {
            Map<String, Object> shot = shotById.get(nodeId.substring(5));
            if (shot == null) {
                return "kind=shot; 该镜头已不存在（不可更新）";
            }
            return "kind=shot; shotId=" + shot.get("id") + "; visual=" + shot.get("visual")
                    + "; narration=" + shot.get("narration") + "; plannedSeconds=" + shot.get("plannedSeconds")
                    + "; cameraMove=" + shot.get("cameraMove");
        }
        if ("media".equals(kind)) {
            return "kind=media; mediaId=" + node.get("refId") + "; label=" + node.get("label")
                    + ";（仅元数据，未做画面分析）";
        }
        if ("note".equals(kind)) {
            return "kind=note; text=" + node.get("text");
        }
        if ("brief".equals(kind) || "delivery".equals(kind)) {
            return "kind=" + kind + "; label=" + node.get("label");
        }
        if ("take".equals(kind)) {
            return "kind=take; takeId=" + node.get("refId");
        }
        return "kind=" + kind;
    }
}
