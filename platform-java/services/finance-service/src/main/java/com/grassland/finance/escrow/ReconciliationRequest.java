package com.grassland.finance.escrow;

/** Marketplace settlement reconciliation command. */
public record ReconciliationRequest(String organizationId, String finalDecision) {
    public ReconciliationRequest {
        if (organizationId == null || organizationId.isBlank()) {
            throw new IllegalArgumentException("organizationId is required");
        }
        if (!"for_merchant".equals(finalDecision)
                && !"for_recommender".equals(finalDecision)) {
            throw new IllegalArgumentException("finalDecision is invalid");
        }
    }
}
