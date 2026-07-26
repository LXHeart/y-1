package com.grassland.identity.identityprofile;

import java.time.Instant;

/**
 * 按 session（设备/标签）隔离的活动身份。草场身份域 Slice 2I（HLD D-08 / 10.1）。
 *
 * <p>{@code sessionToken} 为 cookie 里的 sid；{@code activeIdentityType} 为 null 表示该 session 为消费者。
 * 同一账号多设备 = 多行，活动身份互不影响（设备 A 商家 / 设备 B 消费者）。
 *
 * <p>{@code reauthenticatedAt}/{@code authStrength}（V7）：MFA 重认证证明，同样按 session 隔离——
 * 一个设备重认证不提升另一设备权限。edge-bff 签发断言时取出，供 trust 客服终审校验近期性（HLD §11.2）。
 */
public record IdentitySession(
        String sessionToken,
        String accountId,
        String activeIdentityType,
        String deviceId,
        String deviceLabel,
        String ipAddress,
        String userAgent,
        Instant issuedAt,
        Instant lastSeenAt,
        Instant expiresAt,
        Instant reauthenticatedAt,
        String authStrength
) {}
