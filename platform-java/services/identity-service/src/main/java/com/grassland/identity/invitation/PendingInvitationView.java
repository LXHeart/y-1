package com.grassland.identity.invitation;

import java.time.Instant;

/**
 * 被邀请人视角的待接受邀请（{@code GET /api/me/invitations}）。
 *
 * <p>与 {@link Invitation} 的差异：带上组织名（被邀请人只知道邮箱里的组织，不认 UUID），
 * 且**不含** email / 邀请人 / 状态——这些对被邀请人无信息量，少暴露一点是一点。
 * 门店级邀请（storeId 非 null）额外带门店名，接受后落门店成员而非组织成员。
 */
public record PendingInvitationView(
        String id,
        String organizationId,
        String organizationName,
        String storeId,
        String storeName,
        String role,
        Instant expiresAt,
        Instant createdAt
) {}
