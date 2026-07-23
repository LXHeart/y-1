package com.grassland.identity.identityprofile;

import java.time.Instant;

/**
 * 账号级活动身份。草场身份域 Slice 2G（HLD 1.3 事实 2：同一时间仅一个活动身份）。
 *
 * <p>{@code activeIdentityType} 为 null 表示消费者（默认场景）。一行/账号（account_id 为主键）。
 */
public record ActiveIdentity(
        String accountId,
        String activeIdentityType,
        Instant updatedAt
) {}
