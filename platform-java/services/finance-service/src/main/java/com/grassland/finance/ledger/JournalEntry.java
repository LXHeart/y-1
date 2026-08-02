package com.grassland.finance.ledger;

import java.time.Instant;
import java.util.UUID;

/**
 * 账本头：一次资金移动。不可变（只追加；错误经 Reversal Journal 修正，HLD §6.4）。
 *
 * <p>{@code operationId} 是幂等键（{@code reserve:<ref>} 等，复用 credit-bridge 模式）；
 * {@code null} 表示一次性用户动作（credit/withdraw），不参与幂等。
 */
public record JournalEntry(
        UUID id,
        Type type,
        String operationId,
        String currency,
        String organizationId,
        String engagementRef,
        String memo,
        Instant createdAt) {

    public enum Type {
        /** 充值（资金入托管）。 */
        DEPOSIT,
        /** 预留（earmark 给 engagement）。 */
        RESERVE,
        /** 释放（返还商家）。 */
        RELEASE,
        /** 捕获（结算：拆分 payout + fee）。 */
        CAPTURE,
        /** 冲正（Reversal Journal：全额退商家 + 回扣 payout/fee）。 */
        REVERSE,
        /** 提现（推荐官出账）。 */
        WITHDRAW,
        /** 迁移期回填存量余额。 */
        OPENING;

        public String dbValue() {
            return name();
        }
    }
}
