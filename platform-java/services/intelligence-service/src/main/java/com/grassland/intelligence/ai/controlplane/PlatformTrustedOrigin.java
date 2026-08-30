package com.grassland.intelligence.ai.controlplane;

import java.time.Instant;
import java.util.UUID;

/**
 * 受信平台端点行（{@code platform_trusted_origin}，任务书 #58 决策 B）。
 *
 * <p>{@code origin} 是 {@code scheme://host[:port]} 归一形态（无 path，端口缺省按 scheme 补齐）——
 * 平台模型 base-url 的 SSRF 校验只比 origin，路径不参与，故 baseUrl 可带 {@code /v1}。
 */
public record PlatformTrustedOrigin(
        UUID id,
        String origin,
        String label,
        boolean enabled,
        int version,
        UUID updatedBy,
        Instant updatedAt,
        Instant createdAt) {
}
