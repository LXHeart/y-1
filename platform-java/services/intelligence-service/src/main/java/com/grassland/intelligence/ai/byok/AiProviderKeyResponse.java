package com.grassland.intelligence.ai.byok;

import java.time.Instant;
import java.util.UUID;

/**
 * AI Provider BYOK 密钥 API 响应体（不含敏感信息）。
 */
public record AiProviderKeyResponse(
    UUID id,
    String organizationId,
    String capability,
    String provider,
    String baseUrl,
    String model,
    String maskedHint,      // 掩码提示（sk-***xyz）
    boolean enabled,
    Instant createdAt,
    Instant updatedAt
) {
}
