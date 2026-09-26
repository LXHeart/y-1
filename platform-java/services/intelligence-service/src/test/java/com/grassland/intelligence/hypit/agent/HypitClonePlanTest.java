package com.grassland.intelligence.hypit.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.hypit.agent.HypitMaterialGraph.MaterialNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 材料图纯逻辑（任务书 #107-2 C107-16 / TC107-16-02）：节点去重、被 plan 引用的 材料禁删、未引用的可清理、缺口不猜。
 */
class HypitClonePlanTest {

	@Test
	void materialGraphDedupesNodesAndProtectsReferencedMaterials() {
		List<MaterialNode> nodes = HypitMaterialGraph.of(List.of(new MaterialNode("asset-a", "video", "参考原片", 0),
				new MaterialNode("asset-a", "video", "参考原片（重复登记）", 1),
				new MaterialNode("asset-b", "audio", "背景音乐", 2)));
		assertThat(nodes).hasSize(2);
		assertThat(nodes.get(0).assetId()).isEqualTo("asset-a");

		HypitMaterialGraph graph = new HypitMaterialGraph(nodes);
		// plan 引用了 asset-a：禁删；asset-b 未被引用：可清理
		List<String> referenced = List.of("asset-a");
		assertThat(graph.canDelete("asset-a", referenced)).as("被 plan 引用的材料禁删").isFalse();
		assertThat(graph.canDelete("asset-b", referenced)).isTrue();
		// 引用关系不因去重丢语义：去重后的 asset-a 节点仍是引用目标
		assertThat(graph.nodes().stream().map(MaterialNode::assetId)).containsExactly("asset-a", "asset-b");
	}
}
