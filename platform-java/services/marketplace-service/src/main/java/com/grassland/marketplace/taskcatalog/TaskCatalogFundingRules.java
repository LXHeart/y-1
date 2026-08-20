package com.grassland.marketplace.taskcatalog;

/**
 * 任务资金组合规则（任务书 #46 / ADR-D12 D1 组合模式放开）：bounty（商家出资赏金）与
 * freebie_deposit（推荐官预付押金）可同设——两腿资金方向、账本、生命周期独立。仍互斥的是
 * **阶梯佣金 × 押金**：D-02 阶梯结算计划只对 bounty 腿定义，押金叠加语义未定义。
 * 三写入口（create/update/revise）契约层共用；V40 的 {@code chk_task_funding_xor} CHECK 已由 V45 移除。
 */
final class TaskCatalogFundingRules {

    private TaskCatalogFundingRules() {}

    static void validate(TaskRequirements requirements, Long freebieDepositCents) {
        long deposit = freebieDepositCents == null ? 0L : freebieDepositCents;
        if (deposit > 0 && requirements != null && requirements.commissionLadder() != null) {
            throw new IllegalArgumentException("阶梯佣金不能与霸王餐押金同时启用");
        }
    }
}
