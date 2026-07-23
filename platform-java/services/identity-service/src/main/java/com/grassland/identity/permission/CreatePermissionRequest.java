package com.grassland.identity.permission;

import com.grassland.identity.organization.PermissionTier;

/**
 * 商家权限升级申请的请求体。草场身份域 Slice 2H（HLD D-05 地基）。
 *
 * <p>{@code requestedTier} 必须是 {@link PermissionTier} 合法值（basic_publish/finance_transaction；
 * draft 为默认等级无需申请），compact constructor 内校验。服务端另校验须高于当前 org tier。
 * {@code materials} 可选（自由 JSON 字符串）。
 */
public record CreatePermissionRequest(String requestedTier, String materials) {
    public CreatePermissionRequest {
        if (requestedTier == null || requestedTier.isBlank()) {
            throw new IllegalArgumentException("requestedTier is required");
        }
        PermissionTier.fromDb(requestedTier); // 校验为已知等级
    }
}
