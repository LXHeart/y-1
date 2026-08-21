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
        /** 消费者支付入托管。 */
        CONSUMER_PAYMENT,
        /** 未核销订单原路退款。 */
        CONSUMER_REFUND,
        /** 核销后向推荐官、商家和平台分账。 */
        CONSUMER_SPLIT,
        /** AI 积分包购买入账（AI 套餐 v1）。 */
        AI_CREDIT_PURCHASE,
        /** 霸王餐押金预付（推荐官钱包 → 托管，ADR-D12；方向与 RESERVE 相反）。 */
        FREEBIE_RESERVE,
        /** 霸王餐押金退还（托管 → 推荐官钱包，全额无费）。 */
        FREEBIE_REFUND,
        /** 霸王餐押金补偿（托管 → 商家 org 账户）。 */
        FREEBIE_COMPENSATE,
        /** 审判官现金佣金入推荐官钱包（ADR-D18：Dr 费用 / Cr WALLET）。 */
        JUDGE_COMMISSION,
        /** 迁移期回填存量余额。 */
        OPENING;

        public String dbValue() {
            return name();
        }
    }
}
