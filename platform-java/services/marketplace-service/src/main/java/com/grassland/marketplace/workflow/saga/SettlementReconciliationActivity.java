package com.grassland.marketplace.workflow.saga;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** 结算对账 activity（Slice 7B）：权威核对 + 幂等补执行 + 确认后发布结算。每次重试均幂等。 */
@ActivityInterface
public interface SettlementReconciliationActivity {

    @ActivityMethod
    SettlementReconciliationWorkflow.ReconciliationOutcome reconcile(
            SettlementReconciliationWorkflow.ReconciliationInput input);
}
