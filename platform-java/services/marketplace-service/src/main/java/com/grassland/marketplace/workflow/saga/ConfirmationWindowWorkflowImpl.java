package com.grassland.marketplace.workflow.saga;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * 商家确认窗口 workflow 实现（D-03）。复用 {@code marketplace-saga} worker。
 *
 * <p>实现类<b>不加</b> {@code @Component}（同 {@code SettlementWindowWorkflowImpl}）——Temporal Worker 在 workflow
 * 线程每执行 new 一个实例，{@code @WorkflowImpl} 仅作 auto-discovery marker。窗口时长来自 input（controller 读配置
 * 传入），workflow 内不读 env（HLD 9.2 确定性）。
 */
@WorkflowImpl(taskQueues = ApplicationReservationWorkflowImpl.TASK_QUEUE)
public class ConfirmationWindowWorkflowImpl implements ConfirmationWindowWorkflow {

    private final ConfirmationActivity activity = Workflow.newActivityStub(
            ConfirmationActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofMillis(500))
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    @Override
    public ConfirmationOutcome run(ConfirmationInput input) {
        // 确认窗口 Timer：时长来自 input（controller 读配置传入），workflow 内不读 env（确定性）。
        long window = Math.max(0, input.windowSeconds());
        long lead = Math.max(0, input.reminderLeadSeconds());
        // D-03 slice 2 临到期提醒（剩余 lead 秒）：lead<window 正常中段；lead==window（dispatcher 在最后 24h 补启）立即提醒。
        // lead=0 或 lead>window ⇒ 跳过（dev 关闭/新窗口短于生产 24h 前置）。
        if (lead > 0 && lead <= window) {
            Workflow.sleep(Duration.ofSeconds(window - lead));
            activity.notifyExpiring(input);   // 发 ConfirmationWindowExpiring（确定性 eventId，重试 exactly-once）
            Workflow.sleep(Duration.ofSeconds(lead));
        } else {
            Workflow.sleep(Duration.ofSeconds(window));
        }
        // 窗口到期 → 自动确认结算 Command（重验 + 自动确认 + capture/hold）
        return activity.autoConfirmSettle(input);
    }
}
