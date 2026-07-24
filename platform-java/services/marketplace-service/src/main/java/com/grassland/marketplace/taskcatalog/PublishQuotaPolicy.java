package com.grassland.marketplace.taskcatalog;

/**
 * 按 org tier 的发布限额策略（草场 Epic 4 Slice 4B / HLD D-05「硬限额执行留 marketplace」）。纯逻辑 + 静态表。
 *
 * <p>身份断言只带 tier（声明），marketplace 在发布时按 {@link #maxActiveTasks(MerchantTier)} 执行硬限额：
 * DRAFT=0（不可发布）/ BASIC_PUBLISH=5 / FINANCE_TRANSACTION=50。超出 → 409。
 *
 * <p><b>交叉引用（防漂移）</b>：maxActiveTasks 与 {@code identity.permission.PermissionQuotaPolicy.quotaFor}
 * 的同名字段同义。刻意<b>仅</b>暴露 maxActiveTasks，不复制 maxMonthlyTasks/maxTxAmountCents（那两项分别由
 * marketplace 月度/finance 执行，本 slice 不涉及，复制反而招致腐坏）。值变更须同步两边并更新
 * {@code PublishQuotaPolicyTest}。长期去重靠 identity {@code GET /api/organizations/{orgId}/quota} RPC（延后）。
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
}
