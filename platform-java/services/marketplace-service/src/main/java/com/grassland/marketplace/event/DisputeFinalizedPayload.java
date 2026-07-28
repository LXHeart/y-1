package com.grassland.marketplace.event;

/**
 * trust {@code DisputeFinalized} 载荷（Slice 7B）。{@code disputeId} 必填（对账 workflow id 派生自它）；
 * {@code organizationId} 可选（仅作记录，权威 org 由 marketplace 从任务读取）。
 */
public record DisputeFinalizedPayload(
        String disputeId,
        String engagementRef,
        String organizationId,
        String finalDecision) {}
