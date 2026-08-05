package com.grassland.identity.kyb;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** identity 代商家申请的组织作用域 KYB 上传凭据。 */
public record KybUploadTicket(
        UUID id,
        String objectKey,
        URI uploadUrl,
        String method,
        Map<String, String> headers,
        Instant expiresAt
) {}
