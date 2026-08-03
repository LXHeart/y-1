package com.grassland.marketplace.event;

/**
 * trust {@code DisputeFinalized} 载荷（Slice 7B / D-03 F5）。{@code disputeId} 必填（对账 workflow id 派生自它）；
 * {@code organizationId} 可选（仅作记录，权威 org 由 marketplace 从任务读取）。当旧客服案已原子接续
 * standard successor 时，{@code settlementDeferred=true}，本服务只记 inbox、不为旧案启动资金对账。
 */
public record DisputeFinalizedPayload(
        String disputeId,
        String engagementRef,
        String organizationId,
        String finalDecision,
        boolean settlementDeferred,
        String successorDisputeId) {}
