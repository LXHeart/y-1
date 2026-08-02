package com.grassland.finance.ledger;

/**
 * 一条账本明细（账户 + 借贷方向 + 金额恒正）。每条 journal ≥ 2 posting，借贷合计为零（HLD §6.4）。
 *
 * <p>负债/收入类账户：{@code CREDIT} 增余额、{@code DEBIT} 减余额。
 */
public record Posting(LedgerAccount account, Direction direction, long amountCents) {

    public enum Direction {
        DEBIT,
        CREDIT;

        public String dbValue() {
            return name();
        }
    }

    public static Posting debit(LedgerAccount account, long amountCents) {
        return new Posting(account, Direction.DEBIT, amountCents);
    }

    public static Posting credit(LedgerAccount account, long amountCents) {
        return new Posting(account, Direction.CREDIT, amountCents);
    }
}
