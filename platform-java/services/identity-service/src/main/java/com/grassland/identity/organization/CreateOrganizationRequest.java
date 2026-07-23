package com.grassland.identity.organization;

/**
 * 创建商家主体的请求体。Slice 2L 加可选 {@code industry}（行业分类，HLD D-05）。
 *
 * <p>{@code industry} 原样存放（controller 归一化 null→other）；合法性校验在权限申请时由
 * {@code Industry.fromDb} 做（避免 organization↔permission 包循环）。
 */
public record CreateOrganizationRequest(String name, String industry) {
    public CreateOrganizationRequest {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
    }
}
