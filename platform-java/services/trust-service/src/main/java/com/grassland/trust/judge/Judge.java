package com.grassland.trust.judge;

import java.time.Instant;

/**
 * 审判官（草场 Epic 6 Slice 6C / HLD 3.1「符合条件的推荐官」+ §5.5）。
 *
 * <p>{@code accountId} 与 identity 账号同空间（database-per-service 无 FK）；{@code organizationId} 可空=平台级审判官；
 * {@code eligibilityTier} 预留声誉准入（声誉模块未建，本轮用配置阈值 {@code trust.adjudication.judge-eligibility-tier} 占位）。
 * 同组织或显式 {@code judge_conflict} 记录构成利益冲突，抽面板时排除。
 */
public record Judge(
        String id,
        String accountId,
        String organizationId,
        int eligibilityTier,
        boolean active,
        Instant createdAt) {}
