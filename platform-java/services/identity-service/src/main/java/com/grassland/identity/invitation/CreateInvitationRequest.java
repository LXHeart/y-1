package com.grassland.identity.invitation;

import com.grassland.identity.membership.MembershipRole;

/**
 * 发起组织邀请的请求体。
 *
 * <p>与 {@code CreateMembershipRequest} 的差异：收**邮箱**而非 accountId。role 同样仅允许 admin/member。
 * 校验在 compact constructor 内 fail-fast（非法值 → 反序列化失败 → 400）。
 */
public record CreateInvitationRequest(String email, String role) {

    public CreateInvitationRequest {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email is required");
        }
        email = email.trim().toLowerCase();
        if (!isPlausibleEmail(email)) {
            throw new IllegalArgumentException("email is invalid");
        }
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role is required");
        }
        MembershipRole parsed = MembershipRole.fromDb(role);
        if (parsed == MembershipRole.OWNER) {
            throw new IllegalArgumentException("cannot grant owner role via invitation");
        }
        role = parsed.dbValue();
    }

    /**
     * 邮箱形状校验：只挡明显不是邮箱的输入（无 @ / 空 local / 域名无点 / 含空格），不做 RFC 5322 全解析。
     * 真实性由「对方能登录该邮箱对应的账号并接受邀请」保证，而不是靠正则。
     */
    private static boolean isPlausibleEmail(String value) {
        int at = value.indexOf('@');
        if (at <= 0 || at != value.lastIndexOf('@') || at == value.length() - 1) {
            return false;
        }
        String domain = value.substring(at + 1);
        return domain.contains(".") && !domain.startsWith(".") && !domain.endsWith(".")
                && value.chars().noneMatch(Character::isWhitespace);
    }
}
