package com.grassland.intelligence.ai.byok;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 创建 BYOK 密钥请求体（GL-P3-AI-001 Phase 1）。
 */
public record CreateAiProviderKeyRequest(
    @NotBlank(message = "capability 不能为空")
    @Pattern(regexp = "text|image|image_generation|video_generation", message = "capability 必须是 text/image/image_generation/video_generation 之一")
    String capability,

    @NotBlank(message = "provider 不能为空")
    String provider,

    @NotBlank(message = "baseUrl 不能为空")
    String baseUrl,

    String model,  // 可选；某些 provider 不需要指定模型

    @NotBlank(message = "apiKey 不能为空")
    String apiKey  // 明文密钥，将被加密存储
) {
}
