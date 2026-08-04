package com.grassland.intelligence.ai.controlplane;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 创建平台模型配置（POST /api/admin/ai/models，admin）。
 *
 * <p>{@code capability}/{@code modelRole} 是自然键：同一 (capability, model_role) 已存在 → 409。
 * {@code healthStatus} 可空，默认 healthy。
 */
public record CreatePlatformModelRequest(
        @NotBlank(message = "capability 必填") String capability,
        @NotBlank(message = "modelRole 必填")
        @Pattern(regexp = "primary|backup", message = "modelRole 必须是 primary 或 backup") String modelRole,
        @NotBlank(message = "provider 必填") String provider,
        @NotBlank(message = "model 必填") String model,
        @NotBlank(message = "baseUrl 必填") String baseUrl,
        Integer maxConcurrency,
        @Pattern(regexp = "healthy|degraded|unhealthy", message = "healthStatus 必须是 healthy/degraded/unhealthy")
        String healthStatus) {
}
