package com.grassland.identity.organization;

import java.time.Instant;

/** 商家主体（Organization）。草场身份域 Slice 2E 地基实体。 */
public record Organization(
        String id,
        String ownerAccountId,
        String name,
        String status,
        Instant createdAt,
        Instant updatedAt
) {}
