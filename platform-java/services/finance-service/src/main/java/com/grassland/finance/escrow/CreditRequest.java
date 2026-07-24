package com.grassland.finance.escrow;

/**
 * 充值请求体（草场 Epic 4 Slice 4E，sandbox 自充）。{@code amountCents} 须 {@code >= 1}。
 */
public record CreditRequest(Integer amountCents) {
    public CreditRequest {
        if (amountCents == null || amountCents < 1) {
            throw new IllegalArgumentException("amountCents must be >= 1");
        }
    }
}
