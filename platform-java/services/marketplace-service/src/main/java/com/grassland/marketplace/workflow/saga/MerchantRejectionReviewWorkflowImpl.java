package com.grassland.marketplace.workflow.saga;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/** D-03 商家拒绝客服 SLA workflow：Timer 到期只触发 auto-finalize Command（HLD 9.2）。 */
@WorkflowImpl(taskQueues = ApplicationReservationWorkflowImpl.TASK_QUEUE)
public class MerchantRejectionReviewWorkflowImpl implements MerchantRejectionReviewWorkflow {

    private final MerchantRejectionReviewActivity activity = Workflow.newActivityStub(
            MerchantRejectionReviewActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofMillis(500))
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    @Override
    public void run(MerchantRejectionReviewInput input) {
        Workflow.sleep(Duration.ofSeconds(Math.max(0, input.csSlaSeconds())));
        activity.autoFinalize(input);
    }
}
