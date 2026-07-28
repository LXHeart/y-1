package com.grassland.marketplace.workflow.saga;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * 结算对账 workflow 实现（Slice 7B）。复用 {@code marketplace-saga} worker。实现类不加 {@code @Component}
 * （同 {@link ApplicationReservationWorkflowImpl}）。主体仅编排 activity——所有 DB/HTTP/env 读都在 activity 内。
 */
@WorkflowImpl(taskQueues = ApplicationReservationWorkflowImpl.TASK_QUEUE)
public class SettlementReconciliationWorkflowImpl implements SettlementReconciliationWorkflow {

    private final SettlementReconciliationActivity activity = Workflow.newActivityStub(
            SettlementReconciliationActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofMillis(500))
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    @Override
    public ReconciliationOutcome reconcile(ReconciliationInput input) {
        return activity.reconcile(input);
    }
}
