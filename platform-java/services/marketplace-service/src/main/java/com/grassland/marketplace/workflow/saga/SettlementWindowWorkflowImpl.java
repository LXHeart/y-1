package com.grassland.marketplace.workflow.saga;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * 结算窗口 workflow 实现（草场 Epic 5 Slice 5A）。复用 {@code marketplace-saga} worker（同 4F）。
 *
 * <p>注：实现类<b>不加</b> {@code @Component}（同 {@code ApplicationReservationWorkflowImpl}）——Temporal Worker
 * 在 workflow 线程每执行 new 一个实例，{@code @WorkflowImpl} 仅作 auto-discovery marker。
 */
@WorkflowImpl(taskQueues = ApplicationReservationWorkflowImpl.TASK_QUEUE)
public class SettlementWindowWorkflowImpl implements SettlementWindowWorkflow {

    private final SettlementActivity activity = Workflow.newActivityStub(
            SettlementActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofMillis(500))
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    @Override
    public SettlementOutcome run(SettlementInput input) {
        // 结算窗口 Timer：时长来自 input（controller 读配置传入），workflow 内不读 env（确定性）。
        Workflow.sleep(Duration.ofSeconds(Math.max(0, input.windowSeconds())));
        // 窗口到期 → capture Command（重验 + 查争议 + capture/hold）
        return activity.captureSettlement(input);
    }
}
