package com.grassland.intelligence.ai.byok;

import java.time.Instant;

/**
 * 组织 BYOK 回退策略（ADR-D17 / D-11）。
 *
 * <p>组织配置了有效组织密钥后，成员平台回退须本策略显式允许；无行 = 默认不允许。
 */
public record AiOrgByokPolicy(
    String organizationId,
    boolean allowPlatformFallback,
    long version,
    String updatedByAccountId,
    Instant updatedAt
) {
}
