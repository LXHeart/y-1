package com.grassland.marketplace.taskcatalog;

/**
 * 任务资金字段的判定与归一（ADR-D12）：v1 单资金模式——bounty（商家出资赏金）与
 * freebie_deposit（推荐官预付押金）至多一边 &gt;0（契约层 XOR 校验见 {@link TaskCatalogFundingXor}）。
 * apply/accept 冻结赏金快照与 Saga 金额（AcceptanceCommand.amountCents / AcceptanceInput）同源。
 */
final class TaskFunds {

    private TaskFunds() {}

    /** 资金型任务（Slice 4F + ADR-D12）：bounty &gt;0（商家出资赏金）或 freebie deposit &gt;0（推荐官押金）。 */
    static boolean isMonetary(Task task) {
        return (task.bountyCents() != null && task.bountyCents() > 0) || task.isFreebie();
    }

    /** task.bountyCents 归一为 long（null → 0）。accept/create 冻结赏金快照用。 */
    static long bountyOrZero(Task task) {
        return task.bountyCents() == null ? 0L : task.bountyCents();
    }

    /** task.freebieDepositCents 归一为 long（null → 0，ADR-D12）。 */
    static long freebieDepositOrZero(Task task) {
        return task.freebieDepositCents() == null ? 0L : task.freebieDepositCents();
    }

    /** Saga 金额（XOR 保证至多一边 &gt;0）：bounty 优先，否则押金。 */
    static long fundsOrZero(Task task) {
        return bountyOrZero(task) > 0 ? bountyOrZero(task) : freebieDepositOrZero(task);
    }
}
