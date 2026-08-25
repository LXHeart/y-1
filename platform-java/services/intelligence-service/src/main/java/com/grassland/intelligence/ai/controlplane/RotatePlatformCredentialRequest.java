package com.grassland.intelligence.ai.controlplane;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 轮换平台凭据密钥（PUT /api/admin/ai/credentials/{id}/key，admin）。
 *
 * <p>与 {@code RotateAiProviderKeyRequest} 同形。{@code apiKey} 必填——想清空密钥（回落 env 兜底）
 * 应停用该凭据后重建，而不是用空串表达，避免「空串 vs 空格」这类靠约定记忆的语义。
 */
public record RotatePlatformCredentialRequest(
        @NotBlank(message = "apiKey 必填")
        @Size(max = 2048, message = "apiKey 不能超过 2048 字符") String apiKey) {
}
