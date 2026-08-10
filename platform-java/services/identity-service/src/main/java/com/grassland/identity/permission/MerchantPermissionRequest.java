package com.grassland.identity.permission;

import java.time.Instant;

/**
 * 商家权限升级申请。草场身份域 Slice 2H 实体（HLD D-05 地基）；Slice 2L 加 SLA/行业/申诉字段（HLD D-05 完整规则）。
 *
 * <p>{@code requestedTier}/{@code status} 存 DB 小写字符串（按需用 {@link com.grassland.identity.organization.PermissionTier}/{@link PermissionRequestStatus} 转枚举）。
 * {@code materials} 为结构化 JSON（{@code Map<materialType,文本>}，提交时按 tier+行业校验）。
 * {@code industry} 为提交时 org 行业快照；{@code reviewDeadline} 为 SLA 截止；{@code originalRequestId}/{@code appealNote} 仅申诉申请非空。
 * {@code reviewerAccountId}/{@code reviewNote}/{@code industry}/{@code reviewDeadline}/{@code originalRequestId}/{@code appealNote} 可空。
 */
public record MerchantPermissionRequest(
        String id,
        String organizationId,
        String requesterAccountId,
        String requestedTier,
        String materials,
        String status,
        String reviewerAccountId,
        String reviewNote,
        String industry,
        Instant reviewDeadline,
        String originalRequestId,
        String appealNote,
        Instant createdAt,
        Instant updatedAt,
        int version,
        Instant reviewStartedAt,
        Instant slaBreachedAt,
        String autoReviewStatus,
        String autoReviewResult,
        String reviewMode,
        String riskLevel,
        String attachmentIds,
        Instant decisionAt,
        int appealCount
) {}
