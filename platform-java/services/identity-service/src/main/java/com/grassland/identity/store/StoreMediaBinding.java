package com.grassland.identity.store;

import java.time.Instant;

/**
 * store_media 绑定行（#42 D3）。媒体引用 + kind/position + mime/size 快照；
 * 响应白名单由各 controller 手写 toBody，严禁整行序列化进公开端点。
 */
public record StoreMediaBinding(
        String id,
        String organizationId,
        String storeId,
        String mediaReferenceId,
        String kind,
        int position,
        String mimeType,
        Long sizeBytes,
        String uploadedByAccountId,
        Instant createdAt
) {}
