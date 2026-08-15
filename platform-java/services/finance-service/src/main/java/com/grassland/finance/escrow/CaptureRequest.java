package com.grassland.finance.escrow;

/** Optional internal capture override used by D-02 ladder settlement. */
public record CaptureRequest(Long settlementAmountCents) {
    public CaptureRequest {
        if (settlementAmountCents != null && settlementAmountCents < 1) {
            throw new IllegalArgumentException("settlementAmountCents must be >= 1");
        }
    }
}
