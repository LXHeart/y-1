package com.grassland.identity.identityprofile;

import java.time.Instant;

/**
 * 身份切换审计记录（append-only）。草场身份域 Slice 2I 实体（HLD 10.1 身份切换审计）。
 *
 * <p>{@code action} 存 DB 小写字符串（按需用 {@link IdentityAuditAction} 转枚举）；
 * {@code fromIdentityType}/{@code toIdentityType} 可空（切回消费者时 to 为 null；首次激活 from 为 null）。
 */
public record IdentityAuditLog(
        String id,
        String accountId,
        String action,
        String fromIdentityType,
        String toIdentityType,
        String sessionToken,
        String deviceId,
        String ipAddress,
        String userAgent,
        Instant occurredAt
) {}
