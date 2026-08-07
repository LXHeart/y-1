package com.grassland.finance.wallet;

import java.time.Instant;

/**
 * 钱包流水行（append-only）。
 *
 * <p>{@code amountCents} 带符号（入账正 / 出账负）；{@code feeCents} 是该笔原赏金被平台抽走的部分，
 * {@code commissionBonusCents} 是平台另行承担的等级补贴。三者拆开后可如实展示基础净额、补贴和总到账，
 * 不会把补贴误算成商家赏金或负平台费。
 */
public record WalletEntry(
        String id,
        String accountId,
        String entryType,
        long amountCents,
        long feeCents,
        long commissionBonusCents,
        String engagementRef,
        String memo,
        Instant createdAt
) {}
