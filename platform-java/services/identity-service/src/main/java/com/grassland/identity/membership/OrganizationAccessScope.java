package com.grassland.identity.membership;

/** 账号在某组织的访问范围（本人视角，/api/me/organization-scopes 用）。镜像 store 的 StoreAccessScope。 */
public record OrganizationAccessScope(
        String organizationId, String organizationName, String organizationStatus,
        String permissionTier, String role) {}
