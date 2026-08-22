package com.grassland.marketplace.taskcatalog;

/**
 * 任务付费方式规则（PRD §2.2 2026-08-22 决策：三种模式三选一，不可组合——
 * 推翻任务书 #46 / ADR-D12 D1 的组合放开）：bounty（商家出资赏金，含阶梯预算）与
 * freebie_deposit（推荐官预付押金）互斥。存量组合任务的双腿结算机器保留不动，
 * 只是新的创建/修订不得再产出组合。
 * 三写入口（create/update/revise）契约层共用；V40 的 {@code chk_task_funding_xor} CHECK 已由 V45 移除
 * （重建 DB CHECK 会卡存量数据行，约束保持在契约层）。
 */
final class TaskCatalogFundingRules {

    private TaskCatalogFundingRules() {}

    static void validate(TaskRequirements requirements, Long freebieDepositCents, Long bountyCents) {
        long deposit = freebieDepositCents == null ? 0L : freebieDepositCents;
        long bounty = bountyCents == null ? 0L : bountyCents;
        if (deposit > 0 && requirements != null && requirements.commissionLadder() != null) {
            throw new IllegalArgumentException("阶梯佣金不能与霸王餐押金同时启用");
        }
        if (deposit > 0 && bounty > 0) {
            throw new IllegalArgumentException("付费方式只能三选一：霸王餐押金与赏金不能同时设置");
        }
    }
}
