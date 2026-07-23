package com.grassland.identity.store;

import java.time.Instant;

/**
 * 门店粒度成员。草场身份域 Slice 2G 实体（HLD store-membership：门店范围成员和资源授权）。
 *
 * <p>{@code role} 存 DB 小写字符串（manager/staff，按需用 {@link StoreRole#fromDb} 转枚举）。
 */
public record StoreMembership(
        String id,
        String storeId,
        String accountId,
        String role,
        Instant createdAt,
        Instant updatedAt
) {}
