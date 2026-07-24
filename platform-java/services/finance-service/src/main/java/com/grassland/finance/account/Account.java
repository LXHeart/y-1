package com.grassland.finance.account;

import java.time.Instant;

/**
 * 资金账户（ledger 根实体，HLD 5.4「保存金融余额」）。草场 Epic 4 Slice 4D。
 *
 * <p>{@code organizationId} 逻辑引用 identity 的 organization（跨服务无 FK，database-per-service）；
 * 一 org 一账户。{@code balanceCents} 非负（DB CHECK 约束），Saga 预留/扣减将依赖。
 */
public record Account(
        String id,
        String organizationId,
        long balanceCents,
        String currency,
        Instant createdAt,
        Instant updatedAt
) {}
