package com.grassland.identity.organization;

/** 创建商家主体的请求体。 */
public record CreateOrganizationRequest(String name) {
    public CreateOrganizationRequest {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
    }
}
