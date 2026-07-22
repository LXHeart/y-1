package com.grassland.storage;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/**
 * 返回给客户端的上传凭据（HLD 三步上传的第 1 步：申请上传凭据）。
 * 客户端用 {@code method} 把对象体直接 PUT 到 {@code uploadUrl}。
 */
public record UploadTicket(
        String objectKey,
        URI uploadUrl,
        String method,
        Map<String, String> headers,
        Instant expiresAt) {

    public UploadTicket {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        if (method == null || method.isBlank()) {
            method = "PUT";
        }
    }
}
