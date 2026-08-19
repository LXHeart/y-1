package com.grassland.identity.store;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** identity 校验门店 MANAGER 权限后代商家申请的门店媒体上传凭据（#42 D2，镜像 intelligence 票据响应）。 */
public record StoreMediaUploadTicket(
        UUID id,
        String objectKey,
        URI uploadUrl,
        String method,
        Map<String, String> headers,
        Instant expiresAt
) {}
