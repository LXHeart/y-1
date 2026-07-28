package com.grassland.marketplace.settlement;

import java.time.Instant;

/**
 * 结算对账请求（Slice 7B）。一行对应一个 trust {@code DisputeFinalized} 事件，由消费侧在 Inbox 事务内落库，
 * 由 {@link SettlementReconciliationDispatcher} 派发给 {@code SettlementReconciliationWorkflow}。
 *
 * <p>{@code status}: {@code pending}（待派发）/ {@code started}（已派发）/ {@code reconciled}（已结算，已写 EngagementSettled）/
 * {@code blocked}（人工/冲突，写 SettlementReconciliationBlocked，永不写 EngagementSettled）。
 * 非终态（pending/started）在结算读模型上映射为 {@code held}，避免在钱未确认时声称已结算。
 */
public record SettlementReconciliation(
        String sourceEventId,
        String disputeId,
        String applicationId,
        String organizationId,
        String finalDecision,
        String workflowId,
        String status,
        String reason,
        int dispatchAttempt,
        Instant nextDispatchAt,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt) {

    public boolean isTerminal() {
        return "reconciled".equals(status) || "blocked".equals(status);
    }
}
