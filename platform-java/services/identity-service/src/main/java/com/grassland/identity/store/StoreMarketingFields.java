package com.grassland.identity.store;

import com.grassland.identity.auth.IdentityException;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 门店营销字段归一化与帽（任务书 #24 / PRD §2.1）。
 *
 * <p>列表字段帽镜像 marketplace {@code TaskRequirements.items()}：单项 trim、去空白、去重，
 * 单项 ≤300 字、≤20 项；文本字段 blank → null。违规一律 400。
 */
public final class StoreMarketingFields {

    public static final int MAX_ITEMS = 20;
    public static final int MAX_ITEM_LENGTH = 300;
    public static final int MAX_BRAND_TONE_LENGTH = 500;
    public static final int MAX_PRICE_RANGE_LENGTH = 50;
    public static final int MAX_VISIT_NOTES_LENGTH = 1000;

    private StoreMarketingFields() {
    }

    /** 列表字段：null/空列表 → 空列表（清空语义）；单项 trim、去空白、去重，超限 400。 */
    public static List<String> items(List<String> values, String label) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String item = value.trim();
            if (item.length() > MAX_ITEM_LENGTH) {
                throw new IdentityException(400, label + "单项最多 " + MAX_ITEM_LENGTH + " 字");
            }
            normalized.add(item);
        }
        if (normalized.size() > MAX_ITEMS) {
            throw new IdentityException(400, label + "最多 " + MAX_ITEMS + " 项");
        }
        return List.copyOf(normalized);
    }

    /** 文本字段：blank → null；trim 后超帽 400。 */
    public static String optional(String value, int maxLength, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IdentityException(400, label + "最多 " + maxLength + " 字");
        }
        return normalized;
    }

    /** 人均消费（cents）：null 保持；负数 400。 */
    public static Integer averageSpend(Integer value) {
        if (value != null && value < 0) {
            throw new IdentityException(400, "人均消费不能为负数");
        }
        return value;
    }
}
