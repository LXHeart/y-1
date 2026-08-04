package com.grassland.identity.mobile;

import java.time.Instant;

/**
 * refresh_token 行（GL-P3-IDENTITY-001 移动端 token 认证）。
 *
 * <p>{@code tokenHash} = SHA-256(明文 token) 小写 hex——DB 永不存明文。
 * {@code revokedAt} 非空 = 已撤销（软删）；硬删由 {@link RefreshTokenCleanup} 按 retention 执行。
 * {@code metadataJson} 存扩展字段（如 {@code {"deviceInfo": "<X-Device-Info 原值>"}}）。
 */
public record RefreshToken(
        String id,
        String accountId,
        String tokenHash,
        String deviceFingerprint,
        String deviceName,
        Instant lastUsedAt,
        Instant expiresAt,
        Instant revokedAt,
        Instant createdAt,
        String metadataJson) {

    /** 未撤销且未过期。 */
    public boolean active(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
