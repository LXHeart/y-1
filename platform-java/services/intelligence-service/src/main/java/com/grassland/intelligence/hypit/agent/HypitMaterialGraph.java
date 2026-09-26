package com.grassland.intelligence.hypit.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 材料图（任务书 #107-2 C107-16 / TC107-16-02）：工程参考素材的去重登记图。 同一 assetId 只有一个节点；被
 * ClonePlan 引用的材料禁止删除（禁删红线）， 未被引用的才可清理。缺口材料如实登记，进方案 waiting_input。
 */
public record HypitMaterialGraph(List<MaterialNode> nodes) {

	public record MaterialNode(String assetId, String kind, String title, int order) {
	}

	public HypitMaterialGraph {
		nodes = List.copyOf(dedupe(nodes));
	}

	/** 同 assetId 去重（保留首次登记的语义与顺序）。 */
	public static List<MaterialNode> dedupe(List<MaterialNode> raw) {
		Map<String, MaterialNode> byId = new LinkedHashMap<>();
		for (MaterialNode node : raw) {
			byId.putIfAbsent(node.assetId(), node);
		}
		return new ArrayList<>(byId.values());
	}

	public static List<MaterialNode> of(List<MaterialNode> raw) {
		return dedupe(raw);
	}

	/** 被方案引用的材料禁删；未引用的返回可删。 */
	public boolean canDelete(String assetId, List<String> referencedAssetIds) {
		return !referencedAssetIds.contains(assetId);
	}

	/** 便捷重载：默认无引用（测试用）。 */
	public boolean canDelete(String assetId) {
		return canDelete(assetId, List.of());
	}
}
