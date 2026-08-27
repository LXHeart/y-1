package com.grassland.identity.membership;

import java.time.Instant;

/**
 * 组织成员关系。草场身份域 Slice 2F 实体（HLD 5.2 merchant-organization：成员关系和权限委派）。
 *
 * <p>{@code role} 存 DB 小写字符串（owner/admin/member），按需用 {@link MembershipRole#fromDb} 转枚举做权限判定。
 * {@code accountStatus}（任务书 #48）是列表联 app_users 带出的账号状态，缺席（null）= 未选/账号行不存在。
 * {@code username}（任务书 #49）是列表联 account_username 带出的登录名，删除强确认据此输入。
 * {@code storeId/storeRole/storeName}（任务书 #52 池模型）是该成员当前挂靠的门店（至多一店，
 * 决策 D）；全 null = 未分配，主体成员表据此呈现「所属门店/未分配」。
 */
public record Membership(
        String id,
        String organizationId,
        String accountId,
        String role,
        Instant createdAt,
        Instant updatedAt,
        String accountStatus,
        String username,
        String storeId,
        String storeRole,
        String storeName
) {
    /** 兼容无状态视角的旧构造。 */
    public Membership(String id, String organizationId, String accountId, String role,
            Instant createdAt, Instant updatedAt) {
        this(id, organizationId, accountId, role, createdAt, updatedAt, null, null, null, null, null);
    }

    /** 兼容 #48/#49 两期字段的旧构造。 */
    public Membership(String id, String organizationId, String accountId, String role,
            Instant createdAt, Instant updatedAt, String accountStatus, String username) {
        this(id, organizationId, accountId, role, createdAt, updatedAt, accountStatus, username, null, null, null);
    }
}
