package com.grassland.marketplace.workflow;

/**
 * finance escrow 调用瞬态失败（草场 Epic 4 Slice 4F）。reserve/release 遇非预期 HTTP 状态（非 2xx/409/404）抛出，
 * 被 activity 抛给 Temporal 重试。余额不足是 {@link com.grassland.marketplace.workflow.saga.ReserveResult#insufficientFunds()}
 * 正常返回值，不抛此异常。
 */
public class FinanceEscrowException extends RuntimeException {

    public FinanceEscrowException(String message) {
        super(message);
    }
}
