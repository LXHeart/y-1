package com.grassland.finance.wallet;

/**
 * 钱包流水类型。金额符号由类型决定：{@link #TASK_PAYOUT} 为正，{@link #WITHDRAWAL}/{@link #CLAWBACK} 为负。
 */
public enum WalletEntryType {
    /** 任务结算入账（capture 分账）。 */
    TASK_PAYOUT("task_payout"),
    /** 提现出账。 */
    WITHDRAWAL("withdrawal"),
    /** 争议冲正扣回（D-06：已分账后判商家胜诉）。 */
    CLAWBACK("clawback");

    private final String dbValue;

    WalletEntryType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }
}
