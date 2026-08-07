package com.grassland.marketplace.reputation;

import java.util.List;

/** 全量更新等级策略；expectedVersion 防止两个管理员相互覆盖。 */
public record UpdateReputationPolicyRequest(Long expectedVersion, List<ReputationLevelRuleInput> levels) {}
