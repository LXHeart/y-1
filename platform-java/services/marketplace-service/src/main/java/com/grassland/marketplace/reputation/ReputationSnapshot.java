package com.grassland.marketplace.reputation;

/** 管理端、公开端和 trust 内部端点共享的单次一致性评估结果。 */
public record ReputationSnapshot(String accountId, ReputationStats stats, ReputationPolicy policy,
                                 Lv5Admission admission, ReputationEvaluation evaluation) {}
