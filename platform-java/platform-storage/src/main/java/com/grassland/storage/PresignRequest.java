package com.grassland.storage;

import java.util.Map;

/**
 * 申请上传凭据的请求。
 *
 * @param key 对象 key（由调用方生成，如 {scope}/{uuid}）
 * @param contentType MIME 类型，会同时写入 presigned 的 Content-Type
 * @param expiresSeconds presigned URL 有效期（秒）
 * @param metadata 附加对象元数据（S3 metadata），不可变
 * @param contentLength 可选的精确对象字节数；非空时纳入 SigV4 签名并要求客户端发送同值 Content-Length
 */
public record PresignRequest(
        String key,
        String contentType,
        long expiresSeconds,
        Map<String, String> metadata,
        Long contentLength) {

    public PresignRequest(String key, String contentType, long expiresSeconds, Map<String, String> metadata) {
        this(key, contentType, expiresSeconds, metadata, null);
    }

    public PresignRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (contentLength != null && contentLength < 0) {
            throw new IllegalArgumentException("contentLength must be >= 0");
        }
    }
}
