package com.grassland.marketplace.workflow.saga;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * 结算对账 workflow（Slice 7B）。争议终局后，权威核对 trust + finance 状态、幂等补执行缺失的钱动作、
 * 确认资金到位后才发布最终结算。复用 {@code marketplace-saga} worker。
 *
 * <p>workflow 主体只编排 activity（HLD 9.2 确定性铁律：无 DB/HTTP/env 读）。{@link ReconciliationInput}
 * 的 {@code sourceEventId} 作幂等锚点；{@code disputeId} 用于读 trust 权威终局。
 */
@WorkflowInterface
public interface SettlementReconciliationWorkflow {

    @WorkflowMethod
    ReconciliationOutcome reconcile(ReconciliationInput input);

    /** 对账入参（确定性，来自 settlement_reconciliation 行）。 */
    record ReconciliationInput(
            String sourceEventId, String disputeId, String applicationId, String finalDecision) {}

    /** 对账结局：reconciled（已写 EngagementSettled）/ blocked（写 SettlementReconciliationBlocked）。 */
    record ReconciliationOutcome(String status, String reason) {
        public static ReconciliationOutcome reconciled() {
            return new ReconciliationOutcome("reconciled", null);
        }

        public static ReconciliationOutcome blocked(String reason) {
            return new ReconciliationOutcome("blocked", reason);
        }
    }
}
