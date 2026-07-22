package com.grassland.marketplace.storage;

/** 申请上传凭据的请求体。scope 决定对象 key 前缀（如 marketplace/tasks）。 */
public record CreateUploadRequest(String contentType, String scope) {
    public CreateUploadRequest {
        if (scope == null || scope.isBlank()) {
            scope = "general";
        }
    }
}
