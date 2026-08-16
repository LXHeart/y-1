package com.grassland.finance.wallet;

/**
 * 钱包流水类型。金额符号由类型决定：{@link #TASK_PAYOUT} 为正，{@link #WITHDRAWAL}/{@link #CLAWBACK} 为负。
 */
public enum WalletEntryType {
    /** 任务结算入账（capture 分账）。 */
    TASK_PAYOUT("task_payout"),
    /** 到店消费订单核销后的推荐佣金。 */
    COMMERCE_COMMISSION("commerce_commission"),
    /** 提现出账。 */
    WITHDRAWAL("withdrawal"),
    /** 争议冲正扣回（D-06：已分账后判商家胜诉）。 */
    CLAWBACK("clawback"),
    /** 霸王餐押金预付出账（ADR-D12：报名被接受时进平台托管，负）。 */
    FREEBIE_RESERVE("freebie_reserve"),
    /** 霸王餐押金退收入账（ADR-D12：达标全额返还，正，无平台费）。 */
    FREEBIE_REFUND("freebie_refund");

    private final String dbValue;

    WalletEntryType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }
}
