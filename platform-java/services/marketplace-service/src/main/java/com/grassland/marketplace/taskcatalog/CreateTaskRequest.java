package com.grassland.marketplace.taskcatalog;

/**
 * 发布任务请求体。草场 Epic 4 Slice 4A。
 *
 * <p>{@code organizationId}/{@code title} 必填（compact 构造校验）；{@code description}/{@code contentForm}/{@code platform} 可选。
 * owner 由断言 caller 决定（非请求体）。organizationId 为发布者声明，跨服务归属校验留 4B+。
 */
public record CreateTaskRequest(
        String organizationId,
        String title,
        String description,
        String contentForm,
        String platform
) {
    public CreateTaskRequest {
        if (organizationId == null || organizationId.isBlank()) {
            throw new IllegalArgumentException("organizationId is required");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
    }
}
