package com.grassland.intelligence.hypit.agent;

import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * Agent 工具范围（任务书 #107-2 C107-14 / K11 最小授权）：每个 agent job 声明 允许的工具集合；dispatcher
 * 在派发前核验，越权一律拒绝。read_only scope 只含 检索/读取/状态查询，不含任何触发构建或写回的工具。
 *
 * <p>
 * C107F-04（107-fix-1 D-04/W19）：intent→默认工具集收敛。调用方 scope 只可缩不可扩—— 服务端在投递时按
 * {@link #converge} 收敛，后续 action 越权在派发处拒绝。
 */
public record HypitAgentScope(boolean readOnly, Set<String> allowedTools) {

	public static final String TOOL_KNOWLEDGE_SEARCH = "knowledge.search";
	public static final String TOOL_KNOWLEDGE_READ = "knowledge.read";
	public static final String TOOL_BUILD_STATUS = "build.status";
	public static final String TOOL_BUILD_SUBMIT = "build.submit";
	public static final String TOOL_BUILD_CANCEL = "build.cancel";
	public static final String TOOL_OUTPUT_ARCHIVE = "output.archive";
	public static final String TOOL_MUTATION_APPLY = "mutation.apply";
	public static final String TOOL_PACKAGES_BUILD = "packages.build";
	public static final String TOOL_PACKAGES_PACK = "packages.pack";
	public static final String TOOL_SNAPSHOT = "snapshot";
	public static final String TOOL_FEEDBACK_MUTATE = "feedback.mutate";

	/** 五个合法 intent（API-F04）。 */
	public static final Set<String> INTENTS = Set.of("analyze", "plan", "author", "review", "revise");

	public static HypitAgentScope readOnlyScope() {
		return new HypitAgentScope(true, Set.of(TOOL_KNOWLEDGE_SEARCH, TOOL_KNOWLEDGE_READ, TOOL_BUILD_STATUS));
	}

	public static HypitAgentScope executeScope() {
		return new HypitAgentScope(false, Set.of(TOOL_KNOWLEDGE_SEARCH, TOOL_KNOWLEDGE_READ, TOOL_BUILD_STATUS,
				TOOL_BUILD_SUBMIT, TOOL_BUILD_CANCEL, TOOL_OUTPUT_ARCHIVE));
	}

	/** author/revise：execute 基座 + 自定义组件/变更集写回类（D-04）。 */
	public static HypitAgentScope authorScope() {
		return new HypitAgentScope(false,
				Set.of(TOOL_KNOWLEDGE_SEARCH, TOOL_KNOWLEDGE_READ, TOOL_BUILD_STATUS, TOOL_BUILD_SUBMIT,
						TOOL_BUILD_CANCEL, TOOL_OUTPUT_ARCHIVE, TOOL_MUTATION_APPLY, TOOL_PACKAGES_BUILD,
						TOOL_PACKAGES_PACK));
	}

	/** review：只读基座 + 审阅取证类（snapshot/feedback，D-04）。 */
	public static HypitAgentScope reviewScope() {
		return new HypitAgentScope(false, Set.of(TOOL_KNOWLEDGE_SEARCH, TOOL_KNOWLEDGE_READ, TOOL_BUILD_STATUS,
				TOOL_SNAPSHOT, TOOL_FEEDBACK_MUTATE));
	}

	/** plan：analyze ∩ author（D-04）。 */
	public static HypitAgentScope planScope() {
		return readOnlyScope();
	}

	public static HypitAgentScope scopeForIntent(String intent) {
		return switch (intent) {
			case "analyze" -> readOnlyScope();
			case "author", "revise" -> authorScope();
			case "review" -> reviewScope();
			case "plan" -> planScope();
			default -> throw new IntelligenceException(HttpStatus.BAD_REQUEST.value(), "hypit_invalid_input",
					"intent 必须是 " + INTENTS + " 之一。");
		};
	}

	/**
	 * 投递时收敛（RULE-F03）：intent 默认集 ∩ 调用方集（只缩不扩）。返回收敛后的 scope； narrowed=true
	 * 表示调用方声明的工具被剔除过（调用方记 scope_narrowed 事件，不 400）。
	 */
	public static Converged converge(String intent, java.util.Collection<String> callerAllowedTools) {
		HypitAgentScope base = scopeForIntent(intent);
		if (callerAllowedTools == null || callerAllowedTools.isEmpty()) {
			return new Converged(base, false);
		}
		Set<String> merged = new LinkedHashSet<>();
		boolean narrowed = false;
		for (String tool : callerAllowedTools) {
			if (base.allowedTools().contains(tool)) {
				merged.add(tool);
			} else {
				narrowed = true;
			}
		}
		// 收敛结果为空：保留 intent 最小只读集（不因调用方全写错而落出零工具假集）。
		if (merged.isEmpty()) {
			return new Converged(readOnlyScope(), true);
		}
		boolean readOnly = readOnlyScope().allowedTools().containsAll(merged);
		return new Converged(new HypitAgentScope(readOnly, merged), narrowed);
	}

	public record Converged(HypitAgentScope scope, boolean narrowed) {
	}

	/**
	 * C107F2-14（RULE-08）：planner 可见集 = scope ∩ 工具注册表实际登记集。 未实现工具绝不进 planner
	 * 可见集合——注册表缺项时在此收敛，而不是让 dispatch 落默认 501。
	 */
	public HypitAgentScope confinedTo(java.util.Collection<String> registered) {
		Set<String> kept = new LinkedHashSet<>(allowedTools());
		kept.retainAll(registered);
		return new HypitAgentScope(readOnly, kept);
	}

	/** 越权拒绝：scope 之外的工具一律不可见、不可派发。 */
	public void assertAllowed(String tool) {
		if (!allowedTools().contains(tool)) {
			throw new IntelligenceException(HttpStatus.FORBIDDEN.value(), "hypit_agent_scope",
					"agent scope 不允许工具 " + tool);
		}
	}
}
