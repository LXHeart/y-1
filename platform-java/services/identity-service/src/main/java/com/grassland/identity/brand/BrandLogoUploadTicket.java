package com.grassland.identity.brand;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** identity 完成 ADMIN+ 授权后代商家申请的品牌 Logo 上传凭据（#32 D6，镜像 intelligence 票据响应）。 */
public record BrandLogoUploadTicket(
        UUID id,
        String objectKey,
        URI uploadUrl,
        String method,
        Map<String, String> headers,
        Instant expiresAt
) {}
