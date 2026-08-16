package com.grassland.identity.store;

import java.time.Instant;
import java.util.List;

/**
 * 门店详细资料。GL-P3-MERCHANT-001。
 *
 * <p>镜像 {@code store_profile} 表全字段。任务书 #24：新增 PRD §2.1 营销/品牌字段
 * （列表字段 DB 为 {@code text[]}，领域层为不可变列表，null/空数组统一成空列表）。
 */
public record StoreProfile(
        String storeId,
        String address,                       // JSONB: {province,city,district,address,longitude,latitude}
        String phone,
        String businessHours,                // JSONB: [{dayOfWeek,openTime,closeTime}]
        String description,
        List<String> categories,             // 主营品类
        List<String> signatureItems,         // 特色产品/服务
        List<String> sellingPoints,          // 推荐卖点
        List<String> mustEmphasize,          // 必须强调
        List<String> forbiddenPhrases,       // 禁止表达
        List<String> allowedTags,            // 可使用标签
        String brandTone,                    // 品牌语气
        String priceRange,                   // 价格区间（自由文本）
        Integer averageSpendCents,           // 人均消费（cents）
        String visitNotes,                   // 交通/停车/预约/到店注意
        String status,
        Instant submittedAt,
        Instant reviewedAt,
        String reviewerAccountId,
        String reviewNote,
        Instant createdAt,
        Instant updatedAt
) {}
