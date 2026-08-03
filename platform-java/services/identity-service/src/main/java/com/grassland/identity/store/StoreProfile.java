package com.grassland.identity.store;

import java.time.Instant;

/**
 * 门店详细资料。GL-P3-MERCHANT-001。
 *
 * <p>镜像 {@code store_profile} 表全字段。
 */
public record StoreProfile(
        String storeId,
        String address,                       // JSONB: {province,city,district,address,longitude,latitude}
        String phone,
        String businessHours,                // JSONB: [{dayOfWeek,openTime,closeTime}]
        String description,
        String status,                        // active/inactive
        Instant createdAt,
        Instant updatedAt
) {}
