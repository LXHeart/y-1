package com.grassland.identity.store;

import java.util.List;

/**
 * 门店公开资料白名单视图（任务书 #24 Stage 2）。
 *
 * <p><b>只回这些字段</b>：严禁整行序列化——不含 KYB 审核列、org owner、permission_tier、内部备注。
 * 供公开详情页、feed enrichment 与 AI 商家上下文快照消费。
 */
public record StorePublicProfile(
        String storeId,
        String storeName,
        String address,                       // JSONB: {province,city,district,address,longitude,latitude}
        String phone,
        String businessHours,                // JSONB: [{dayOfWeek,openTime,closeTime}]
        String description,
        List<String> categories,
        List<String> signatureItems,
        String priceRange,
        Integer averageSpendCents,
        String visitNotes,
        List<String> sellingPoints,
        String brandTone,
        List<String> mustEmphasize,
        List<String> forbiddenPhrases,
        List<String> allowedTags) {
}
