package com.grassland.marketplace.taskcatalog;

/**
 * 发布任务请求体。草场 Epic 4 Slice 4A（4B 加 maxSlots）。
 *
 * <p>{@code organizationId}/{@code title} 必填（compact 构造校验）；{@code description}/{@code contentForm}/{@code platform}
 * 可选；{@code maxSlots} 可空（null=不限名额），若给须 {@code >= 1}（0 无意义）。owner 由断言 caller 决定（非请求体）。
 * organizationId 须等于 caller 的 org（TaskController 资源级自查，Slice 4B）。
 */
public record CreateTaskRequest(
        String organizationId,
        String title,
        String description,
        String contentForm,
        String platform,
        Integer maxSlots
) {
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
    }
}
