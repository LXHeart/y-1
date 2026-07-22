package com.grassland.storage;

import java.time.Instant;

/** 已存储对象的元数据（不含内容字节）。 */
public record StoredObject(
        String key,
        long contentLength,
        String contentType,
        String etag,
        Instant lastModified) {
}
