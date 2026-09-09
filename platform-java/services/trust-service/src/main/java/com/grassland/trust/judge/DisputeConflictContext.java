package com.grassland.trust.judge;

import java.util.HashSet;
import java.util.Set;

/**
 * 案件冲突上下文（审查修复 02 / R04）：原告与被告的权威账号 + 涉案组织。
 *
 * <p>
 * 所有抽签入口（初审、自动开庭、手动自愈、补席、重开与重审）共用同一份回避集， 不在各自入口拼
 * exclusions。资格合法（等级/考试/准入/挂起）与「和本案无利益冲突」 是两个独立维度：前者由
 * {@link JudgeEligibilityService} 判定，后者由本上下文承载。
 */
public record DisputeConflictContext(String disputeId, String organizationId, Set<String> partyAccountIds) {

	public DisputeConflictContext {
		partyAccountIds = partyAccountIds == null ? Set.of() : Set.copyOf(partyAccountIds);
	}

	/** 附加既有面板成员等额外回避，返回合并后的排除集（不改动本上下文）。 */
	public Set<String> exclusionsWith(Set<String> additional) {
		Set<String> merged = new HashSet<>(partyAccountIds);
		if (additional != null) {
			merged.addAll(additional);
		}
		return merged;
	}
}
