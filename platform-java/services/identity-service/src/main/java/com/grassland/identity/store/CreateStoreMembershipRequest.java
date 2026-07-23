package com.grassland.identity.store;

/**
 * 新增门店成员的请求体。草场身份域 Slice 2G。
 *
 * <p>role 必须是 {@link StoreRole} 合法值（manager/staff），compact constructor 内校验。
 */
public record CreateStoreMembershipRequest(String accountId, String role) {
    public CreateStoreMembershipRequest {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId is required");
        }
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role is required");
        }
        StoreRole.fromDb(role); // 校验为已知门店角色，非法抛 IllegalArgumentException
    }
}
