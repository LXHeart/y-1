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
        List<String> evidenceRequirements,
        /** Optional single-metric D-02 policy; null keeps the fixed-payout contract. */
        CommissionLadder commissionLadder,
        /** 任务书 #23 / ADR-D13：点赞互动配置块；仅 contentForm=interaction 任务非空（三入口交叉校验）。 */
        Interaction interaction) {

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
        return new TaskRequirements(null, List.of(), List.of(), null, null, List.of(), List.of(), null, null);
    }

    /** Backward-compatible constructor for callers that do not configure a ladder. */
    public TaskRequirements(String productServiceInfo, List<String> mustInclude,
                            List<String> forbiddenContent, Instant publishStartAt, Instant publishEndAt,
                            List<String> metricRequirements, List<String> evidenceRequirements) {
        this(productServiceInfo, mustInclude, forbiddenContent, publishStartAt, publishEndAt,
                metricRequirements, evidenceRequirements, null, null);
    }

    public TaskRequirements(String productServiceInfo, List<String> mustInclude,
                            List<String> forbiddenContent, Instant publishStartAt, Instant publishEndAt,
                            List<String> metricRequirements, List<String> evidenceRequirements,
                            CommissionLadder commissionLadder) {
        this(productServiceInfo, mustInclude, forbiddenContent, publishStartAt, publishEndAt,
                metricRequirements, evidenceRequirements, commissionLadder, null);
    }

    /**
     * 任务书 #23 R1：content_form 受控值集（null=未指定，沿用现状）。
     */
    public static boolean isValidContentForm(String contentForm) {
        return contentForm == null || "image".equals(contentForm) || "video".equals(contentForm)
                || "article".equals(contentForm) || "interaction".equals(contentForm);
    }

    /** 是否点赞互动任务（按 content_form 受控值判，ADR-D13 核心建模：第四个受控值而非新任务大类）。 */
    public static boolean isInteractionForm(String contentForm) {
        return "interaction".equals(contentForm);
    }

    /**
     * 任务书 #23 R2 交叉校验：{@code contentForm=interaction ⇔ requirements.interaction 非空}。
     * 挂 create/update/revise 三入口（请求 record 构造后调用，IllegalArgumentException→400）。
     */
    public static void validateInteractionBinding(String contentForm, TaskRequirements requirements) {
        boolean interactionForm = isInteractionForm(contentForm);
        boolean hasBlock = requirements != null && requirements.interaction() != null;
        if (interactionForm && !hasBlock) {
            throw new IllegalArgumentException("点赞互动任务必须配置互动目标（targetUrl + actionType）");
        }
        if (!interactionForm && hasBlock) {
            throw new IllegalArgumentException("仅内容形式为「点赞互动」的任务可配置互动块");
        }
    }

    public static TaskRequirements normalize(TaskRequirements value) {
        return value == null ? empty() : value;
    }

    /**
     * 互动配置块（ADR-D13 R2）。{@code targetUrl} 复用核验引擎既有 {@link LinkUrlGuard}（SSRF/私网拒绝），
     * 不新写一套；{@code actionType} 受控 like|favorite|follow|comment（评论为缺口清偿之九，提交侧 L1 词库审核）。
     * v1 单目标单次动作。
     */
    public record Interaction(String targetUrl, String actionType) {

        public Interaction {
            if (targetUrl != null) targetUrl = targetUrl.trim();
            if (targetUrl == null || targetUrl.isEmpty()) {
                throw new IllegalArgumentException("互动目标链接不能为空");
            }
            LinkUrlGuard.validate(targetUrl);  // http(s)、无凭据、私网/环回拒绝（同核验引擎守卫）
            if (actionType != null) actionType = actionType.trim().toLowerCase();
            if (!"like".equals(actionType) && !"favorite".equals(actionType) && !"follow".equals(actionType)
                    && !"comment".equals(actionType)) {
                throw new IllegalArgumentException("动作类型必须是 like / favorite / follow / comment");
            }
        }
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
