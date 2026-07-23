package com.grassland.identity.permission;

import com.grassland.identity.organization.PermissionTier;
import java.util.Map;

/**
 * 商家权限升级申请的请求体。草场身份域 Slice 2H（HLD D-05 地基）；Slice 2L 材料 schema 化 + 行业。
 *
 * <p>{@code requestedTier} 必须是 {@link PermissionTier} 合法值（basic_publish/finance_transaction；
 * draft 为默认等级无需申请），compact constructor 内校验。服务端另校验须高于当前 org tier。
 * {@code materials} 为 {@code Map<materialType,文本>}，服务端按 tier+行业校验必填（{@link PermissionMaterialPolicy}）。
 * {@code industry} 可选（覆盖 org 行业；合法性由 {@code Industry.fromDb} 校验）。
 */
public record CreatePermissionRequest(String requestedTier, Map<String, String> materials, String industry) {
    public CreatePermissionRequest {
        if (requestedTier == null || requestedTier.isBlank()) {
            throw new IllegalArgumentException("requestedTier is required");
        }
        PermissionTier.fromDb(requestedTier); // 校验为已知等级
    }
}
