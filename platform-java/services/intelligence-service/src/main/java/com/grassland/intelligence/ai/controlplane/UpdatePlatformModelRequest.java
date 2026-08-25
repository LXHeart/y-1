package com.grassland.intelligence.ai.controlplane;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;

/**
 * 修订平台模型配置（PUT /api/admin/ai/models/{capability}/{modelRole}，admin）。
 *
 * <p>{@code capability}/{@code modelRole} 取自路径；此处只带可变字段。修订 = 版本 +1 + disable 旧 + history。
 * {@code healthStatus} 可空，默认 healthy。
 */
public record UpdatePlatformModelRequest(
        @NotBlank(message = "provider 必填")
        @Pattern(regexp = "qwen|openai-compatible|sandbox",
                message = "平台 provider 必须是 qwen、openai-compatible 或 sandbox") String provider,
        @NotBlank(message = "model 必填") String model,
        @NotBlank(message = "baseUrl 必填") String baseUrl,
        @Min(value = 1, message = "maxConcurrency 必须大于 0")
        @Max(value = 1000, message = "maxConcurrency 不能超过 1000") Integer maxConcurrency,
        @Pattern(regexp = "healthy|degraded|unhealthy", message = "healthStatus 必须是 healthy/degraded/unhealthy")
        String healthStatus) {
}
