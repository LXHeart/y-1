package com.grassland.intelligence.ai.byok;

import jakarta.validation.constraints.NotBlank;

/**
 * 更换密钥请求（密钥轮换，GL-P3-AI-001 Phase 1）。
 */
public record RotateAiProviderKeyRequest(
    @NotBlank(message = "apiKey 不能为空")
    String apiKey  // 新的明文密钥
) {
}
