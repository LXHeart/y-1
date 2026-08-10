package com.grassland.identity.store;

/** A store membership projected with the organization context required by store-scoped clients. */
public record StoreAccessScope(
        String storeId,
        String storeName,
        String storeStatus,
        String organizationId,
        String organizationName,
        String organizationStatus,
        String permissionTier,
        String role) {}
