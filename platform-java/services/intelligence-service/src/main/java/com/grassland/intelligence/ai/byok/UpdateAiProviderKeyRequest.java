package com.grassland.intelligence.ai.byok;

/**
 * 更新密钥配置请求（不含 apiKey，GL-P3-AI-001 Phase 1）。
 */
public record UpdateAiProviderKeyRequest(
    String baseUrl,
    String model
) {
}
