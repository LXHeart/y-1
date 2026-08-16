package com.grassland.identity.invitation;

import com.grassland.identity.membership.MembershipRole;
import com.grassland.identity.store.StoreRole;

/**
 * 发起邀请的请求体。
 *
 * <p>与 {@code CreateMembershipRequest} 的差异：收**邮箱**而非 accountId。组织级（storeId 缺省）
 * role 仅允许 admin/member；门店级（storeId 提供）role 为 staff/manager——与直接添加门店成员的
 * 请求体同档。校验在 compact constructor 内 fail-fast（非法值 → 反序列化失败 → 400）。
 */
public record CreateInvitationRequest(String email, String role, String storeId) {

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
        if (storeId == null || storeId.isBlank()) {
            // 组织级邀请：role ∈ admin/member（owner 不可经邀请授予）。
            MembershipRole parsed = MembershipRole.fromDb(role);
            if (parsed == MembershipRole.OWNER) {
                throw new IllegalArgumentException("cannot grant owner role via invitation");
            }
            role = parsed.dbValue();
            storeId = null;
        } else {
            // 门店级邀请：role ∈ staff/manager（与直接添加门店成员的请求体同档）。
            try {
                java.util.UUID.fromString(storeId.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("storeId is invalid");
            }
            if (StoreRole.fromDb(role) == null) {
                throw new IllegalArgumentException("store invitation role must be staff or manager");
            }
            role = role.trim().toLowerCase();
            storeId = storeId.trim();
        }
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
