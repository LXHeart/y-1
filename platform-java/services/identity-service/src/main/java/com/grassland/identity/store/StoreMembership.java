package com.grassland.identity.store;

import java.time.Instant;

/**
 * 门店粒度成员。草场身份域 Slice 2G 实体（HLD store-membership：门店范围成员和资源授权）。
 *
 * <p>{@code role} 存 DB 小写字符串（manager/staff，按需用 {@link StoreRole#fromDb} 转枚举）。
 * {@code accountStatus}（任务书 #48）是列表联 app_users 带出的账号状态（active/suspended/
 * pending_review/rejected），缺席（null）= 账号行不存在或旧调用方未选——只作展示，鉴权一律
 * 以 app_users 为准。{@code username}（任务书 #49）是列表联 account_username 带出的登录名。
 */
public record StoreMembership(
        String id,
        String storeId,
        String accountId,
        String role,
        Instant createdAt,
        Instant updatedAt,
        String accountStatus,
        String username
) {
    /** 兼容无状态视角的旧构造（测试/内部构造用）。 */
    public StoreMembership(String id, String storeId, String accountId, String role,
            Instant createdAt, Instant updatedAt) {
        this(id, storeId, accountId, role, createdAt, updatedAt, null, null);
    }
}
