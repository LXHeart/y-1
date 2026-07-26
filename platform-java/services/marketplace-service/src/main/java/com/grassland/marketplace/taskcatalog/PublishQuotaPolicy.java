package com.grassland.marketplace.taskcatalog;

/**
 * 按 org tier 的发布限额策略（草场 Epic 4 Slice 4B / HLD D-05「硬限额执行留 marketplace」）。纯逻辑 + 静态表。
 *
 * <p>身份断言只带 tier（声明），marketplace 在发布时按 {@link #maxActiveTasks(MerchantTier)} 执行硬限额：
 * DRAFT=0（不可发布）/ BASIC_PUBLISH=5 / FINANCE_TRANSACTION=50。超出 → 409。
 *
 * <p><b>交叉引用（防漂移）</b>：三项均与 {@code identity.permission.PermissionQuotaPolicy.quotaFor} 的同名字段同义
 * （D-05 硬限额执行）。值变更须同步两边并更新 {@code PublishQuotaPolicyTest}（该测试锁定全部三项）。
 * 长期去重靠 identity {@code GET /api/organizations/{orgId}/quota} RPC（marketplace 具备熔断器后评估）。
 *
 * <p>执行分工：{@link #maxActiveTasks}/{@link #maxMonthlyTasks} 在发布时由 {@code TaskController} 执行；
 * {@link #maxTxAmountCents} 在发布时按 {@code bountyCents} 预校验（此处有真实商家断言、带 tier），
 * finance 侧对<b>商家直调</b> reserve 另有同值兜底（服务断言 tier=null 豁免，见 {@code FinanceTxQuotaPolicy}）。
 */
public final class PublishQuotaPolicy {

    private PublishQuotaPolicy() {}

    /** 某 tier 同时允许的活跃（非 closed）任务数上限。 */
    public static int maxActiveTasks(MerchantTier tier) {
        return switch (tier) {
            case DRAFT -> 0;
            case BASIC_PUBLISH -> 5;
            case FINANCE_TRANSACTION -> 50;
        };
    }

    /** 某 tier 每自然月允许新建的任务数上限（D-05「额度」月度维度）。 */
    public static int maxMonthlyTasks(MerchantTier tier) {
        return switch (tier) {
            case DRAFT -> 0;
            case BASIC_PUBLISH -> 20;
            case FINANCE_TRANSACTION -> 500;
        };
    }

    /** 某 tier 单笔资金型任务的赏金上限（分）。0 = 不可发布资金型任务（无交易权限）。 */
    public static long maxTxAmountCents(MerchantTier tier) {
        return switch (tier) {
            case DRAFT -> 0L;
            case BASIC_PUBLISH -> 0L;
            case FINANCE_TRANSACTION -> 10_000_000L;
        };
    }
}
