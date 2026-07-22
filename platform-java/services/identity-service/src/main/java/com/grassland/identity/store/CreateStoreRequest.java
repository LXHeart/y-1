package com.grassland.identity.store;

/** 创建门店的请求体（仿 {@link com.grassland.identity.organization.CreateOrganizationRequest} 的 compact constructor 校验）。 */
public record CreateStoreRequest(String name) {
    public CreateStoreRequest {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
    }
}
