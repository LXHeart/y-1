package com.grassland.intelligence.ai.controlplane;

import java.time.Instant;
import java.util.UUID;

/**
 * 平台模型配置实体（model-control-plane，HLD §5.6 / §12.3）。
 *
 * <p>平台按 {@code capability} 配置主备（{@code model_role}=primary/backup）模型 / provider / base_url /
 * 并发 / 健康。版本化（{@code version} 每次 admin 变更 +1，单调），存量 Run 按其时点版本结算 / 复现
 * （HLD §2.3 / §6.2「模型必须保存使用时的版本快照」，由 TaskContext 冻结 version）。
 * 同一 {@code (capability, model_role)} 同时只有一个 {@code enabled=true} 行（V7 部分唯一索引强制）。
 */
public record PlatformModelConfig(
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
        String updatedBy,
        Instant createdAt,
        Instant updatedAt) {

    public static final String ROLE_PRIMARY = "primary";
    public static final String ROLE_BACKUP = "backup";

    public static final String HEALTH_HEALTHY = "healthy";
    public static final String HEALTH_DEGRADED = "degraded";
    public static final String HEALTH_UNHEALTHY = "unhealthy";

    public boolean isHealthy() {
        return HEALTH_HEALTHY.equalsIgnoreCase(healthStatus);
    }

    public boolean isAvailable() {
        return isHealthy() || HEALTH_DEGRADED.equalsIgnoreCase(healthStatus);
    }
}
