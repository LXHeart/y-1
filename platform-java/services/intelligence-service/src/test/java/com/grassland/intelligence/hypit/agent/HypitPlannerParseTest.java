package com.grassland.intelligence.hypit.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.hypit.agent.HypitAgentStepService.PlannedActions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

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

	// ── C107F3-10（TC-F3-10-02 单元半边 / W71）：旧构造与旧 planner 输入兼容回归 ──

	/**
	 * W20 增量后旧 4 参构造 + 无 reference_context 观察的旧输入形状必须原样工作（§2.9/§7.3： 旧 checkpoint
	 * 无标志按旧流程）。上方两个解析反例不动。
	 */
	@Test
	void legacyConstructorAndObservationFreePlanStillParses() {
		HypitAgentStepService steps = serviceWithPassThroughSchema();
		String raw = "{\"actions\":[{\"kind\":\"knowledge.search\",\"input\":{\"query\":\"legacy\"}}]}";
		PlannedActions planned = steps.parsePlan(raw, HypitAgentScope.readOnlyScope(), 5);
		assertThat(planned).as("旧构造+旧输入（无上下文观察）解析不受增量影响").isNotNull();
		assertThat(planned.actions()).hasSize(1);
		assertThat(planned.actions().get(0).kind()).isEqualTo("knowledge.search");
		assertThat(planned.actions().get(0).inputJson()).contains("\"query\":\"legacy\"");
	}

	/**
	 * reference_context 观察以「参考分析上下文（冻结快照」标签注入 user prompt——与动作观察分段
	 * 呈现、不称作已执行动作；上下文段先于动作观察段（§6.4 首项语义）。经 mock frozen 截获真实
	 * messages（输入侧组装面），planner 输出侧解析由既有反例覆盖。
	 */
	@Test
	@SuppressWarnings("unchecked")
	void referenceContextObservationRenderedAsContextNotAction() {
		String raw = "{\"actions\":[{\"kind\":\"knowledge.search\",\"input\":{\"query\":\"ctx\"}}]}";
		com.grassland.intelligence.ai.run.FrozenTextExecutionService frozen = mock(
				com.grassland.intelligence.ai.run.FrozenTextExecutionService.class);
		HypitAgentToolRegistry registry = mock(HypitAgentToolRegistry.class);
		when(registry.validateInput(anyString(), any())).thenReturn(null);
		when(registry.visibleTools())
				.thenReturn(new java.util.LinkedHashSet<>(HypitAgentScope.authorScope().allowedTools()));
		HypitAgentStepService steps = new HypitAgentStepService(registry,
				mock(com.grassland.intelligence.hypit.job.HypitJobActionRepository.class), frozen,
				mock(HypitReviewService.class));
		when(frozen.executeIndependent(any(), any(), any(), anyInt(), any(), any(), any()))
				.thenReturn(Mono.just(new com.grassland.intelligence.ai.run.FrozenTextExecutionService.Traced<>(raw,
						null, null, null, null, false)));
		Map<String, Object> context = new LinkedHashMap<>();
		context.put("version", 1);
		context.put("analysisId", "ra-legacy-check");
		context.put("mediaHash", "ab".repeat(32));
		Map<String, Object> contextObservation = new LinkedHashMap<>();
		contextObservation.put("kind", "reference_context");
		contextObservation.put("referenceContext", context);
		Map<String, Object> actionObservation = new LinkedHashMap<>();
		actionObservation.put("actionId", "act-1");
		actionObservation.put("result", Map.of("tool", "knowledge.search", "hits", List.of("doc")));
		PlannedActions planned = steps.plan("account", "author", "brief", HypitAgentScope.authorScope(), 5, List.of(),
				List.of(contextObservation, actionObservation), 0).block(java.time.Duration.ofSeconds(10));
		assertThat(planned).isNotNull();
		ArgumentCaptor<List<com.grassland.intelligence.ai.ChatMessage>> captor = ArgumentCaptor
				.forClass((Class) List.class);
		verify(frozen).executeIndependent(any(), any(), captor.capture(), anyInt(), any(), any(), any());
		String user = captor.getValue().stream()
				.filter(message -> "user".equals(message.role()) && message.content() != null)
				.map(com.grassland.intelligence.ai.ChatMessage::content).findFirst().orElse("");
		assertThat(user).contains("参考分析上下文（冻结快照");
		assertThat(user).contains("ra-legacy-check");
		assertThat(user).contains("轮动作的真实观察");
		assertThat(user.indexOf("参考分析上下文")).as("上下文段先于动作观察段（首项语义）").isLessThan(user.indexOf("轮动作的真实观察"));
	}
}
