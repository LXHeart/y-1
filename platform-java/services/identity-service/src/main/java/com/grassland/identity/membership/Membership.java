package com.grassland.identity.membership;

import java.time.Instant;

/**
 * 组织成员关系。草场身份域 Slice 2F 实体（HLD 5.2 merchant-organization：成员关系和权限委派）。
 *
 * <p>{@code role} 存 DB 小写字符串（owner/admin/member），按需用 {@link MembershipRole#fromDb} 转枚举做权限判定。
 */
public record Membership(
        String id,
        String organizationId,
        String accountId,
        String role,
        Instant createdAt,
        Instant updatedAt
) {}
