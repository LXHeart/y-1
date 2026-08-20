package com.grassland.marketplace.taskcatalog;

import java.util.List;

/** 任务书 #27：批量操作请求（1–50 条）。 */
public record BatchOperationRequest(List<String> applicationIds) {
    public BatchOperationRequest {
        if (applicationIds == null || applicationIds.isEmpty()) {
            throw new IllegalArgumentException("applicationIds is required");
        }
        if (applicationIds.size() > 50) {
            throw new IllegalArgumentException("applicationIds must not exceed 50");
        }
    }
}
