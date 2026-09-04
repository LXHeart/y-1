package com.grassland.trust.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * 客服直裁 SLA workflow 实现（任务书 #74 卡 A）。与主审判 workflow 同 task-queue；
 * 单段 Timer + 幂等 activity——客服提前终裁时 activity no-op，到点未裁则按
 * for_recommender 自动终局（事件 DisputeFinalized 附 {@code auto: true}）。
 */
@WorkflowImpl(taskQueues = DisputeAdjudicationWorkflowImpl.TASK_QUEUE)
public class CsDirectSlaWorkflowImpl implements CsDirectSlaWorkflow {

    private final AdjudicationActivity activity = Workflow.newActivityStub(
            AdjudicationActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofMillis(500))
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    @Override
    public void run(CsDirectSlaInput input) {
        Workflow.sleep(Duration.ofSeconds(Math.max(0, input.slaSeconds())));
        activity.autoFinalizeCsDirect(input.disputeId());
    }
}
