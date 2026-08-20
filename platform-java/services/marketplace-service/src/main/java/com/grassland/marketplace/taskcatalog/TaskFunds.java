package com.grassland.marketplace.taskcatalog;

/**
 * 任务资金字段的判定与归一（ADR-D12 / 任务书 #46 组合模式）：bounty（商家出资赏金腿）与
 * freebie_deposit（推荐官预付押金腿）可同设，两腿独立（金额各自冻结、各自结算）。
 * apply/accept 冻结两列快照；Saga 金额双值（AcceptanceCommand.amountCents=bounty 腿 /
 * freebieAmountCents=押金腿）。阶梯 × 押金互斥由 {@link TaskCatalogFundingRules} 拦。
 */
final class TaskFunds {

    private TaskFunds() {}

    /** 资金型任务（Slice 4F + ADR-D12）：bounty &gt;0（商家出资赏金）或 freebie deposit &gt;0（推荐官押金）。 */
    static boolean isMonetary(Task task) {
        return (task.bountyCents() != null && task.bountyCents() > 0) || task.isFreebie();
    }

    /** task.bounty_cents 归一为 long（null → 0）。accept/create 冻结赏金快照用。 */
    static long bountyOrZero(Task task) {
        return task.bountyCents() == null ? 0L : task.bountyCents();
    }

    /** task.freebie_deposit_cents 归一为 long（null → 0，ADR-D12）。 */
    static long freebieDepositOrZero(Task task) {
        return task.freebieDepositCents() == null ? 0L : task.freebieDepositCents();
    }

    /**
     * Saga 命令台账金额（V25 CHECK 把 &gt;0 兼作 monetary 标志）：bounty 优先，否则押金。
     * 仅用于 task_acceptance_command.amount_cents 审计列——真实两腿金额以 task_application
     * 冻结快照为准（任务书 #46：reserveFunds activity 读快照，不读该值）。
     */
    static long commandAmountCents(Task task) {
        return bountyOrZero(task) > 0 ? bountyOrZero(task) : freebieDepositOrZero(task);
    }
}
