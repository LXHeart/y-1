package com.grassland.finance.ledger;

import java.util.Objects;

/**
 * 账本账户（复合键：{@code type + owner + 可选 ref}）。不建独立 accounts 表——余额由 posting {@code SUM} 派生。
 *
 * <p>账户语义（草场资金拓扑，HLD §6.4 / ADR-D01）：
 * <ul>
 *   <li>{@code ESCROW:{orgId}} — 平台对商家的托管负债（投影 = {@code finance_account.balance_cents}）。</li>
 *   <li>{@code RESERVE:{orgId}:{engagementRef}} — 已 earmark 待结算的预留池（派生，无投影行）。</li>
 *   <li>{@code WALLET:{accountId}} — 推荐官可提现余额（投影 = {@code recommender_wallet.balance_cents}）。</li>
 *   <li>{@code FEE} — 平台抽成收入（owner/ref 为 null）。</li>
 *   <li>{@code SUBSIDY_EXPENSE} — 平台承担的等级佣金补贴费用（owner/ref 为 null）。</li>
 *   <li>{@code JUDGE_COMMISSION_EXPENSE} — 审判官现金佣金费用（ADR-D18，owner/ref 为 null）。</li>
 *   <li>{@code EXTERNAL:{channel}} — PSP/存管对手方（{@code channel=sandbox} 为 stub；真实 PSP 时此腿接 {@code PaymentProviderAdapter}）。</li>
 * </ul>
 *
 * <p>负债/收入类账户：{@code CREDIT} 增、{@code DEBIT} 减；余额 = {@code SUM(credit) - SUM(debit)}。
 */
public record LedgerAccount(Type type, String owner, String ref) {

    /** 账户类型（存 {@code posting.account_type}）。 */
    public enum Type {
        ESCROW,
        RESERVE,
        /** 消费订单核销前的托管负债；owner=orderRef。 */
        CONSUMER_ESCROW,
        WALLET,
        FEE,
        /** 平台为等级权益承担的佣金补贴费用。借记增加、冲正时贷记回冲。 */
        SUBSIDY_EXPENSE,
        /** AI 积分包销售收入（AI 套餐 v1）；收入类，credit 增。 */
        AI_CREDIT_REVENUE,
        /** 审判官现金佣金费用（ADR-D18）；借记增加。与 SUBSIDY_EXPENSE 分科目供经营报表区分。 */
        JUDGE_COMMISSION_EXPENSE,
        EXTERNAL;

        public String dbValue() {
            return name();
        }
    }

    public LedgerAccount {
        Objects.requireNonNull(type, "type");
    }

    public static LedgerAccount escrow(String orgId) {
        return new LedgerAccount(Type.ESCROW, orgId, null);
    }

    public static LedgerAccount reserve(String orgId, String engagementRef) {
        return new LedgerAccount(Type.RESERVE, orgId, engagementRef);
    }

    /** 霸王餐押金 earmark 池（ADR-D12）：owner=出资推荐官（非 org），ref=engagementRef。 */
    public static LedgerAccount freebieReserve(String recommenderAccountId, String engagementRef) {
        return new LedgerAccount(Type.RESERVE, recommenderAccountId, engagementRef);
    }

    public static LedgerAccount consumerEscrow(String orderRef) {
        return new LedgerAccount(Type.CONSUMER_ESCROW, orderRef, null);
    }

    public static LedgerAccount wallet(String accountId) {
        return new LedgerAccount(Type.WALLET, accountId, null);
    }

    public static LedgerAccount fee() {
        return new LedgerAccount(Type.FEE, null, null);
    }

    /** AI 积分包销售收入账户（AI 套餐 v1，平台级无 owner）。 */
    public static LedgerAccount aiCreditRevenue() {
        return new LedgerAccount(Type.AI_CREDIT_REVENUE, null, null);
    }

    public static LedgerAccount subsidy() {
        return new LedgerAccount(Type.SUBSIDY_EXPENSE, null, null);
    }

    /** 审判官现金佣金费用账户（ADR-D18，平台级无 owner）。 */
    public static LedgerAccount judgeCommissionExpense() {
        return new LedgerAccount(Type.JUDGE_COMMISSION_EXPENSE, null, null);
    }

    public static LedgerAccount external(String channel) {
        return new LedgerAccount(Type.EXTERNAL, channel, null);
    }
}
