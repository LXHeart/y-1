package com.grassland.finance.escrow;

/**
 * 预留请求体（草场 Epic 4 Slice 4E）。{@code engagementRef} 必填（Saga 幂等键）；{@code amountCents} 须 {@code >= 1}。
 */
public record ReserveRequest(String engagementRef, Integer amountCents) {
    public ReserveRequest {
        if (engagementRef == null || engagementRef.isBlank()) {
            throw new IllegalArgumentException("engagementRef is required");
        }
        if (amountCents == null || amountCents < 1) {
            throw new IllegalArgumentException("amountCents must be >= 1");
        }
    }
}
