package com.grassland.identity.store;

import java.time.Instant;

/**
 * intelligence 批量换 URL 端点的单项解析结果（#42 D5）。仅通过四重过滤的媒体才会出现；
 * {@code expiresAt} 为媒体资产 TTL，非 URL 过期时间（同 intelligence MediaServiceDownloadResponse 口径）。
 */
public record ResolvedMedia(
        String mimeType,
        Long sizeBytes,
        String downloadUrl,
        Instant expiresAt
) {}
