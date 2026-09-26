package com.grassland.intelligence.hypit.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.security.IntelligenceException;
import org.junit.jupiter.api.Test;

/**
 * 工具范围白名单（C107-14 / K11 最小授权）：read-only scope 只允许检索/读取/ 状态；写工具一律
 * FORBIDDEN，execute scope 也拿不到未登记工具。
 */
class HypitToolScopeTest {

	@Test
	void readOnlyScopeRefusesWriteTools() {
		HypitAgentScope scope = HypitAgentScope.readOnlyScope();
		scope.assertAllowed(HypitAgentScope.TOOL_KNOWLEDGE_SEARCH);
		scope.assertAllowed(HypitAgentScope.TOOL_KNOWLEDGE_READ);
		scope.assertAllowed(HypitAgentScope.TOOL_BUILD_STATUS);
		assertThatThrownBy(() -> scope.assertAllowed(HypitAgentScope.TOOL_BUILD_SUBMIT))
				.isInstanceOfSatisfying(IntelligenceException.class, error -> {
					assertThat(error.status()).isEqualTo(403);
					assertThat(error.code()).isEqualTo("hypit_agent_scope");
				});
		assertThatThrownBy(() -> scope.assertAllowed(HypitAgentScope.TOOL_MUTATION_APPLY))
				.isInstanceOf(IntelligenceException.class);
	}

	@Test
	void executeScopeAddsControlledWriteToolsButNothingUnregistered() {
		HypitAgentScope scope = HypitAgentScope.executeScope();
		scope.assertAllowed(HypitAgentScope.TOOL_BUILD_SUBMIT);
		scope.assertAllowed(HypitAgentScope.TOOL_OUTPUT_ARCHIVE);
		assertThatThrownBy(() -> scope.assertAllowed("shell.exec")).isInstanceOf(IntelligenceException.class);
	}
}
