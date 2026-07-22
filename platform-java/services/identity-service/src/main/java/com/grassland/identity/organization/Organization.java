package com.grassland.identity.organization;

import java.time.Instant;

/**
 * 商家主体（Organization）。草场身份域 Slice 2E 地基实体；Slice 2F 加 {@code permissionTier}（三级商家准入）。
 *
 * <p>{@code permissionTier} 存 DB 小写字符串（draft/basic_publish/finance_transaction），按需用 {@link PermissionTier#fromDb} 转枚举。
 */
public record Organization(
        String id,
        String ownerAccountId,
        String name,
        String status,
        String permissionTier,
        Instant createdAt,
        Instant updatedAt
) {}
