package com.grassland.intelligence.credits;

/** Finance-authoritative result of reconciling a priced AI reservation to actual provider cost. */
public record CreditSettlement(
        String accountId,
        CreditFeature feature,
        String operationId,
        CreditCharge.Source source,
        String creditsCentsPolicyVersion,
        long reservedCents,
        int reservedCredits,
        long actualCents,
        int actualCredits,
        int adjustmentCredits,
        boolean deduplicated) {}
