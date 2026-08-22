package com.grassland.marketplace.taskcatalog;

import java.time.Instant;

/**
 * 发布任务请求体（immediate-publish，{@code POST /api/tasks}）。草场 Epic 4 Slice 4A（4B 加 maxSlots；4F 加 bountyCents；
 * GL-P1-TASK-001 Stage 1 加 applicationDeadline）。
 *
 * <p>{@code organizationId}/{@code title} 必填（compact 构造校验）；{@code description}/{@code contentForm}/{@code platform}
 * 可选；{@code maxSlots} 可空（null=不限名额），若给须 {@code >= 1}（0 无意义）。{@code bountyCents} 可空
 * （null/0=非资金型任务，accept 走 4B 直连；{@code >0}=资金型赏金分，accept 经资金预留 Saga，Slice 4F），若给须 {@code >= 0}。
 * {@code applicationDeadline} 可空（null=无时间截止；PRD「指定时间」截止，apply 时判）。
 * owner 由断言 caller 决定（非请求体）。organizationId 须等于 caller 的 org（TaskController 资源级自查，Slice 4B）。
 */
public record CreateTaskRequest(
        String organizationId,
        String title,
        String description,
        String contentForm,
        String platform,
        Integer maxSlots,
        Long bountyCents,
        Instant applicationDeadline,
        Integer minRecommenderLevel,
        String storeId,
        TaskRequirements requirements,
        Integer autoAcceptMinLevel,
        Long freebieDepositCents
) {
    public CreateTaskRequest(String organizationId, String title, String description, String contentForm,
                             String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline,
                             Integer minRecommenderLevel) {
        this(organizationId, title, description, contentForm, platform, maxSlots, bountyCents,
                applicationDeadline, minRecommenderLevel, null, TaskRequirements.empty(), null, null);
    }

    public CreateTaskRequest {
        if (organizationId == null || organizationId.isBlank()) {
            throw new IllegalArgumentException("organizationId is required");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (maxSlots != null && maxSlots < 1) {
            throw new IllegalArgumentException("maxSlots must be >= 1");
        }
        if (bountyCents != null && bountyCents < 0) {
            throw new IllegalArgumentException("bountyCents must be >= 0");
        }
        if (freebieDepositCents != null && freebieDepositCents < 0) {
            throw new IllegalArgumentException("freebieDepositCents must be >= 0");
        }
        TaskCatalogFundingRules.validate(requirements, freebieDepositCents, bountyCents);
        if (!TaskRequirements.isValidContentForm(contentForm)) {
            throw new IllegalArgumentException("内容形式必须是 image / video / article / interaction");
        }
        TaskRequirements.validateInteractionBinding(contentForm, requirements);
        requirements = TaskRequirements.normalize(requirements);
        if (minRecommenderLevel != null && (minRecommenderLevel < 1 || minRecommenderLevel > 5)) {
            throw new IllegalArgumentException("minRecommenderLevel must be between 1 and 5");
        }
        if (autoAcceptMinLevel != null && (autoAcceptMinLevel < 1 || autoAcceptMinLevel > 5)) {
            throw new IllegalArgumentException("autoAcceptMinLevel must be between 1 and 5");
        }
    }
}
