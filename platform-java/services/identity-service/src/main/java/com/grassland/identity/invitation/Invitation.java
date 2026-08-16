package com.grassland.identity.invitation;

import java.time.Instant;

/**
 * 组织成员邀请。按**邮箱**而非 account_id 发起——邀请人不可能知道对方 UUID。
 *
 * <p>{@code email} 存归一化小写；{@code acceptedByAccountId} 在被邀请人接受时落地（此前为 null）。
 * 是否过期由 {@link #isExpired(Instant)} 按 {@code expiresAt} 判定，不写回 status。
 */
public record Invitation(
        String id,
        String organizationId,
        String storeId,
        String email,
        String role,
        String status,
        String invitedByAccountId,
        String acceptedByAccountId,
        Instant expiresAt,
        Instant createdAt
) {
    /** 邀请是否已过期（到期时刻按已过期算，与 SQL 的 {@code expires_at > now()} 过滤一致）。 */
    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public boolean isPending() {
        return InvitationStatus.PENDING.dbValue().equalsIgnoreCase(status);
    }
}
