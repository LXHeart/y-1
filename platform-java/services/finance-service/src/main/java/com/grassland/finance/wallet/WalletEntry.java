package com.grassland.finance.wallet;

import java.time.Instant;

/**
 * 钱包流水行（append-only）。
 *
 * <p>{@code amountCents} 带符号（入账正 / 出账负）；{@code feeCents} 是该笔入账被平台抽走的部分
 * （毛额 = amount + fee），单独记下来是为了能向推荐官如实展示「任务赏金 ¥500，平台服务费 ¥0，到账 ¥500」，
 * 而不是只给一个到账数字。
 */
public record WalletEntry(
        String id,
        String accountId,
        String entryType,
        long amountCents,
        long feeCents,
        String engagementRef,
        String memo,
        Instant createdAt
) {}
