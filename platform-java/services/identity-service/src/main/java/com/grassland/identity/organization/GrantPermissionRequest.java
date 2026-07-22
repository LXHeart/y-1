package com.grassland.identity.organization;

/**
 * 升级商家准入权限的请求体。草场身份域 Slice 2F。
 *
 * <p>tier 必须是 {@link PermissionTier} 的合法 dbValue（draft/basic_publish/finance_transaction），compact constructor 内校验。
 */
public record GrantPermissionRequest(String tier) {
    public GrantPermissionRequest {
        if (tier == null || tier.isBlank()) {
            throw new IllegalArgumentException("tier is required");
        }
        PermissionTier.fromDb(tier); // 校验为已知 tier，非法抛 IllegalArgumentException
    }
}
