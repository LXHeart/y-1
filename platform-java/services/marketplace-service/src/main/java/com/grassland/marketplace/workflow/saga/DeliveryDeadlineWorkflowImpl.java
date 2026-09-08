package com.grassland.marketplace.workflow.saga;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * 交付看门狗 workflow 实现（任务书 #96 C96-01）。复用 {@code marketplace-saga} worker；不加
 * {@code @Component}（Temporal Worker 每次执行 new 实例，{@code @WorkflowImpl} 仅作 auto-discovery marker，
 * 照 {@link ConfirmationWindowWorkflowImpl}）。
 *
 * <p>时长全部来自 input（派发器按 DB 快照算剩余秒数传入），workflow 内不读 env（HLD 9.2 确定性）：
 * <ol>
 *   <li>睡到「交付截止 - reminderLead」发 {@code DeliveryDeadlineExpiring} 提醒（lead=0 或已过提醒点则跳过）。</li>
 *   <li>睡满交付截止 → 补救窗（快照时差 = remedy 窗长，延期整体后移）。</li>
 *   <li>终结线到点 → {@code terminateDeliveryTimeout} activity：行级守卫（{@code remedy_deadline_at <= now()}、
 *       仍无提交、未确认/未退出）不满足即 abort，有责终结单边胜出。</li>
 * </ol>
 */
@WorkflowImpl(taskQueues = ApplicationReservationWorkflowImpl.TASK_QUEUE)
public class DeliveryDeadlineWorkflowImpl implements DeliveryDeadlineWorkflow {

    private final DeliveryLifecycleActivity activity = Workflow.newActivityStub(
            DeliveryLifecycleActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofMillis(500))
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    @Override
    public DeliveryOutcome run(DeliveryDeadlineInput input) {
        long toDeadline = input.remainingToDeadlineSeconds();
        long toRemedyEnd = input.remainingToRemedyEndSeconds();
        long lead = input.reminderLeadSeconds();
        // 提醒（临交付截止 lead 秒）：lead < 剩余 正常中段；lead >= 剩余 ⇒ 已过提醒点，跳过。
        if (lead > 0 && lead < toDeadline) {
            Workflow.sleep(Duration.ofSeconds(toDeadline - lead));
            activity.notifyDeliveryExpiring(input);
            Workflow.sleep(Duration.ofSeconds(lead));
        } else {
            Workflow.sleep(Duration.ofSeconds(toDeadline));
        }
        // 交付截止 → 补救窗终结线（差额即快照的补救窗长；补启晚到时该差额已被压缩，不重置窗口）。
        Workflow.sleep(Duration.ofSeconds(Math.max(0, toRemedyEnd - toDeadline)));
        return activity.terminateDeliveryTimeout(input);
    }
}
