package com.grassland.intelligence.ai.controlplane;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建平台凭据（POST /api/admin/ai/credentials，admin）。
 *
 * <p>{@code apiKey} <b>可空</b>——sandbox provider 无需密钥，回填/兜底场景也允许先建无密钥凭据（D1/D23①）。
 * 同 {@code (provider, baseUrl)} 已有有效凭据 → 409（同目的地两把 key 无法确定用哪把）。
 */
public record CreatePlatformCredentialRequest(
        @NotBlank(message = "name 必填")
        @Size(max = 128, message = "name 不能超过 128 字符") String name,
        @NotBlank(message = "provider 必填")
        @Pattern(regexp = PlatformProviderNames.PATTERN,
                message = PlatformProviderNames.MESSAGE) String provider,
        @NotBlank(message = "baseUrl 必填")
        @Size(max = 1000, message = "baseUrl 不能超过 1000 字符") String baseUrl,
        @Size(max = 2048, message = "apiKey 不能超过 2048 字符") String apiKey) {
}
