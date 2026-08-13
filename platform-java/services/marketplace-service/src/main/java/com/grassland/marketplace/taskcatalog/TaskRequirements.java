package com.grassland.marketplace.taskcatalog;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

/** Structured, versioned task contract consumed by PRD section 4.12 creation snapshots. */
public record TaskRequirements(
        String productServiceInfo,
        List<String> mustInclude,
        List<String> forbiddenContent,
        Instant publishStartAt,
        Instant publishEndAt,
        List<String> metricRequirements,
        List<String> evidenceRequirements) {

    private static final int MAX_PRODUCT_SERVICE_LENGTH = 2_000;
    private static final int MAX_ITEMS = 20;
    private static final int MAX_ITEM_LENGTH = 300;

    public TaskRequirements {
        productServiceInfo = optional(productServiceInfo, MAX_PRODUCT_SERVICE_LENGTH, "产品/服务信息");
        mustInclude = items(mustInclude, "必须包含内容");
        forbiddenContent = items(forbiddenContent, "禁止内容");
        metricRequirements = items(metricRequirements, "指标要求");
        evidenceRequirements = items(evidenceRequirements, "凭证要求");
        if (publishStartAt != null && publishEndAt != null && publishEndAt.isBefore(publishStartAt)) {
            throw new IllegalArgumentException("发布时间结束不能早于开始时间");
        }
    }

    public static TaskRequirements empty() {
        return new TaskRequirements(null, List.of(), List.of(), null, null, List.of(), List.of());
    }

    public static TaskRequirements normalize(TaskRequirements value) {
        return value == null ? empty() : value;
    }

    private static String optional(String value, int maxLength, String label) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(label + "最多 " + maxLength + " 字");
        }
        return normalized;
    }

    private static List<String> items(List<String> values, String label) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            String item = value.trim();
            if (item.length() > MAX_ITEM_LENGTH) {
                throw new IllegalArgumentException(label + "单项最多 " + MAX_ITEM_LENGTH + " 字");
            }
            normalized.add(item);
        }
        if (normalized.size() > MAX_ITEMS) {
            throw new IllegalArgumentException(label + "最多 " + MAX_ITEMS + " 项");
        }
        return List.copyOf(normalized);
    }
}
