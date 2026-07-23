package com.grassland.identity.identityprofile;

import java.time.Instant;

/**
 * 身份档案：账号开通的商家/推荐官身份。草场身份域 Slice 2G 实体（HLD 5.2 identity-profile）。
 *
 * <p>{@code identityType} 存 DB 小写字符串（merchant/recommender，按需用 {@link IdentityType#fromDb} 转枚举）。
 * {@code organizationId} 仅商家身份有值（推荐官为 null）。
 */
public record IdentityProfile(
        String id,
        String accountId,
        String identityType,
        String organizationId,
        String status,
        Instant createdAt,
        Instant updatedAt
) {}
