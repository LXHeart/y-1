package com.grassland.intelligence.hypit.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.hypit.agent.HypitAgentStepService.PlannedActions;
import org.junit.jupiter.api.Test;

/**
 * planner 输出解析单元面（C107F2-37 缺陷 P 回归）：input 携带对象数组（如 mutation.apply 的
 * changes）必须完整解码为 List/Map——旧实现把数组节点交给 只认对象形的 HypitJson.read，所有带数组的规划输出恒 decode
 * failed → author 链 hypit_planner_failed（真实浏览器贯通实录）。
 */
class HypitPlannerParseTest {

	private HypitAgentStepService serviceWithPassThroughSchema() {
		HypitAgentToolRegistry registry = mock(HypitAgentToolRegistry.class);
		when(registry.validateInput(anyString(), any())).thenReturn(null);
		return new HypitAgentStepService(registry,
				mock(com.grassland.intelligence.hypit.job.HypitJobActionRepository.class),
				mock(com.grassland.intelligence.ai.run.FrozenTextExecutionService.class),
				mock(HypitReviewService.class));
	}

	@Test
	void parsePlanAcceptsObjectArrayInputAndPreservesValueTypes() {
		String raw = "{\"actions\":[{\"kind\":\"mutation.apply\",\"input\":{\"applyMode\":\"validated\","
				+ "\"changes\":[{\"path\":\"main.svml\",\"action\":\"put\",\"content\":\"<svml/>\"}]}}]}";
		PlannedActions planned = serviceWithPassThroughSchema().parsePlan(raw, HypitAgentScope.authorScope(), 5);
		assertThat(planned).as("对象数组输入必须可解码").isNotNull();
		assertThat(planned.actions()).hasSize(1);
		assertThat(planned.actions().get(0).kind()).isEqualTo("mutation.apply");
		// 类型保真：applyMode 仍是字符串、changes 数组结构原样进 input_json。
		assertThat(planned.actions().get(0).inputJson()).contains("\"applyMode\":\"validated\"");
		assertThat(planned.actions().get(0).inputJson()).contains("\"changes\":[{");
	}

	@Test
	void parsePlanStillRejectsUnknownKindAndNonArrayActions() {
		HypitAgentStepService steps = serviceWithPassThroughSchema();
		assertThat(steps.parsePlan("{\"actions\":[{\"kind\":\"shell.exec\",\"input\":{}}]}",
				HypitAgentScope.authorScope(), 5)).isNull();
		assertThat(steps.parsePlan("{\"actions\":{\"kind\":\"knowledge.search\"}}", HypitAgentScope.authorScope(), 5))
				.isNull();
	}
}
