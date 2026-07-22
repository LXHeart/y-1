package com.grassland.identity.membership;

/**
 * 新增组织成员的请求体。草场身份域 Slice 2F。
 *
 * <p>role 仅允许 admin/member（owner 不可通过此端点授予；owner 转移另议）。校验在 compact constructor 内 fail-fast。
 */
public record CreateMembershipRequest(String accountId, String role) {
    public CreateMembershipRequest {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId is required");
        }
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role is required");
        }
        MembershipRole parsed = MembershipRole.fromDb(role);
        if (parsed == MembershipRole.OWNER) {
            throw new IllegalArgumentException("cannot grant owner role via this endpoint");
        }
    }
}
