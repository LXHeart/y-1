package com.grassland.intelligence.hypit.agent;

import java.util.Set;

/**
 * Agent 工具范围（任务书 #107-2 C107-14 / K11 最小授权）：每个 agent job 声明 允许的工具集合；dispatcher
 * 在派发前核验，越权一律拒绝。read_only scope 只含 检索/读取/状态查询，不含任何触发构建或写回的工具。
 */
public record HypitAgentScope(boolean readOnly, Set<String> allowedTools) {

	public static final String TOOL_KNOWLEDGE_SEARCH = "knowledge.search";
	public static final String TOOL_KNOWLEDGE_READ = "knowledge.read";
	public static final String TOOL_BUILD_STATUS = "build.status";
	public static final String TOOL_BUILD_SUBMIT = "build.submit";
	public static final String TOOL_BUILD_CANCEL = "build.cancel";
	public static final String TOOL_OUTPUT_ARCHIVE = "output.archive";
	public static final String TOOL_MUTATION_APPLY = "mutation.apply";

	public static HypitAgentScope readOnlyScope() {
		return new HypitAgentScope(true, Set.of(TOOL_KNOWLEDGE_SEARCH, TOOL_KNOWLEDGE_READ, TOOL_BUILD_STATUS));
	}

	public static HypitAgentScope executeScope() {
		return new HypitAgentScope(false, Set.of(TOOL_KNOWLEDGE_SEARCH, TOOL_KNOWLEDGE_READ, TOOL_BUILD_STATUS,
				TOOL_BUILD_SUBMIT, TOOL_BUILD_CANCEL, TOOL_OUTPUT_ARCHIVE));
	}

	/** 越权拒绝：scope 之外的工具一律不可见、不可派发。 */
	public void assertAllowed(String tool) {
		if (!allowedTools().contains(tool)) {
			throw new com.grassland.intelligence.security.IntelligenceException(
					org.springframework.http.HttpStatus.FORBIDDEN.value(), "hypit_agent_scope",
					"agent scope 不允许工具 " + tool);
		}
	}
}
