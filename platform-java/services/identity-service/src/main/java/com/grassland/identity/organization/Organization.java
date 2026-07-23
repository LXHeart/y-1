package com.grassland.identity.organization;

import java.time.Instant;

/**
 * 商家主体（Organization）。草场身份域 Slice 2E 地基实体；Slice 2F 加 {@code permissionTier}（三级商家准入）；
 * Slice 2L 加 {@code industry}（行业分类，HLD D-05）。
 *
 * <p>{@code permissionTier} 存 DB 小写字符串（draft/basic_publish/finance_transaction），按需用 {@link PermissionTier#fromDb} 转枚举。
 * {@code industry} 存 DB 小写字符串（catering/retail/...），按需用 {@code Industry.fromDb} 转枚举；null=未指定。
 */
public record Organization(
        String id,
        String ownerAccountId,
        String name,
        String status,
        String permissionTier,
        String industry,
        Instant createdAt,
        Instant updatedAt
) {}
