package com.grassland.marketplace.event;

public record DisputeFinalizedPayload(
        String disputeId,
        String engagementRef,
        String finalDecision) {}
