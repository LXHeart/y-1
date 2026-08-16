package com.grassland.marketplace.taskcatalog;

/**
 * 任务资金模式 XOR 校验（ADR-D12 D1）：v1 单资金模式——bounty（商家出资赏金）与
 * freebie_deposit（推荐官预付押金）不可同时 &gt;0。组合模式为后续 backlog（同构方向矩阵已论证可行性，
 * 但 Saga 双预留步/结算双腿拆分不值得首期背）。三写入口（create/update/revise）契约层共用；DB 层
 * {@code chk_task_funding_xor} CHECK（V40）兜底。
 */
final class TaskCatalogFundingXor {

    private TaskCatalogFundingXor() {}

    static void validate(Long bountyCents, Long freebieDepositCents) {
        long bounty = bountyCents == null ? 0L : bountyCents;
        long deposit = freebieDepositCents == null ? 0L : freebieDepositCents;
        if (bounty > 0 && deposit > 0) {
            throw new IllegalArgumentException("赏金与霸王餐押金不可同时设置（v1 单资金模式）");
        }
    }
}
