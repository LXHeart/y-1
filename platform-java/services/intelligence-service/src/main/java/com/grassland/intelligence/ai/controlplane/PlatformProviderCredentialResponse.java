package com.grassland.intelligence.ai.controlplane;

import java.time.Instant;
import java.util.UUID;

/**
 * 平台凭据响应（任务书 #47 D5）。
 *
 * <p><b>不含密文也不含明文</b>：只回 {@link #maskedHint}（如 {@code sk-****cdef}）与 {@link #hasKey}。
 * 与 {@code AiProviderKeyResponse} 同口径——密钥写进去就不再出来，换密钥走独立轮换端点。
 */
public record PlatformProviderCredentialResponse(
        UUID id,
        String name,
        String provider,
        String baseUrl,
        boolean hasKey,
        String maskedHint,
        boolean enabled,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static PlatformProviderCredentialResponse from(PlatformProviderCredential c) {
        return new PlatformProviderCredentialResponse(
                c.id(), c.name(), c.provider(), c.baseUrl(), c.hasKey(), c.maskedHint(),
                c.enabled(), c.version(), c.createdAt(), c.updatedAt());
    }
}
