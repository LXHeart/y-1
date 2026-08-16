package com.grassland.identity.store;

import java.util.List;

/**
 * 门店资料草稿写入参数（任务书 #24）。列表字段均已归一化（可为空列表，不可为 null）。
 */
public record StoreProfileDraft(
        String address,
        String phone,
        String businessHours,
        String description,
        List<String> categories,
        List<String> signatureItems,
        List<String> sellingPoints,
        List<String> mustEmphasize,
        List<String> forbiddenPhrases,
        List<String> allowedTags,
        String brandTone,
        String priceRange,
        Integer averageSpendCents,
        String visitNotes) {
}
