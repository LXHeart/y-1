package com.grassland.identity.store;

import java.time.Instant;

/** 门店（商家主体下辖多门店）。草场身份域 Slice 2F 实体（HLD 6.3 MERCHANT_ORGANIZATION ||--o{ STORE）。 */
public record Store(
        String id,
        String organizationId,
        String name,
        String status,
        Instant createdAt,
        Instant updatedAt
) {}
