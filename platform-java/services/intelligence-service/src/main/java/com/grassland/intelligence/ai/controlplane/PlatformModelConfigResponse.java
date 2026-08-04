package com.grassland.intelligence.ai.controlplane;

import java.time.Instant;
import java.util.UUID;

/** 平台模型配置响应（admin 看板 / TaskContext 审计）。不含任何密钥——平台模型凭据在 env/secret，非此表。 */
public record PlatformModelConfigResponse(
        UUID id,
        String capability,
        String modelRole,
        String provider,
        String model,
        String baseUrl,
        Integer maxConcurrency,
        String healthStatus,
        boolean enabled,
        int version,
        Instant createdAt,
        Instant updatedAt) {

    public static PlatformModelConfigResponse from(PlatformModelConfig c) {
        return new PlatformModelConfigResponse(
                c.id(), c.capability(), c.modelRole(), c.provider(), c.model(), c.baseUrl(),
                c.maxConcurrency(), c.healthStatus(), c.enabled(), c.version(), c.createdAt(), c.updatedAt());
    }
}
