package com.grassland.identity.permission;

import java.time.Instant;

/**
 * 商家权限升级申请。草场身份域 Slice 2H 实体（HLD D-05 地基）。
 *
 * <p>{@code requestedTier}/{@code status} 存 DB 小写字符串（按需用 {@link com.grassland.identity.organization.PermissionTier}/{@link PermissionRequestStatus} 转枚举）。
 * {@code materials}/{@code reviewerAccountId}/{@code reviewNote} 可空。
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
        Instant createdAt,
        Instant updatedAt
) {}
