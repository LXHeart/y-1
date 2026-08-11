package com.grassland.finance.wallet;

/**
 * 提现请求体。{@code amountCents} 须 &ge; 1（校验在 compact constructor，非法 → 400）。
 */
public record WithdrawRequest(Long amountCents, String operationId) {
    public WithdrawRequest {
        if (amountCents == null || amountCents < 1) {
            throw new IllegalArgumentException("amountCents must be >= 1");
        }
    }
}
