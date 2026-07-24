package com.grassland.marketplace.workflow.saga;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * 接受报名资金预留 Saga 实现（草场 Epic 4 Slice 4F / HLD 10.2）。
 *
 * <p>编排：{@code beginAcceptance → reserveFunds → { activateEngagement | compensateAcceptance }}。
 * reserve 成功但 activate 失败 → compensate（release + 回退）。RPC 仅在 activity 内（HLD 9.2 确定性铁律）。
 *
 * <p>注：实现类<b>不加</b> {@code @Component}——Temporal Worker 在 workflow 线程每执行 new 一个实例，
 * {@code @WorkflowImpl} 仅作 starter auto-discovery 的 marker 把本类注册到 worker。若成 Spring 单例会在非 workflow
 * 线程实例化，导致字段初始化里的 {@code Workflow.newActivityStub} 抛 "non workflow thread" Error（同 GrasslandDemoWorkflowImpl）。
 */
@WorkflowImpl(taskQueues = ApplicationReservationWorkflowImpl.TASK_QUEUE)
public class ApplicationReservationWorkflowImpl implements ApplicationReservationWorkflow {

    public static final String TASK_QUEUE = "marketplace-saga";

    private final ApplicationReservationActivity activity = Workflow.newActivityStub(
            ApplicationReservationActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofMillis(500))
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    @Override
    public ReservationOutcome run(AcceptanceInput input) {
        if (!activity.beginAcceptance(input)) {
            return ReservationOutcome.aborted();  // 前置校验未过（已终态/名额满/竞态）——未进入资金流
        }
        ReserveResult reserve = activity.reserveFunds(input);
        if (!reserve.reserved()) {
            // 余额不足：无预留，仅回退报名（幂等）
            activity.compensateAcceptance(input, reserve, "insufficient_funds");
            return ReservationOutcome.compensated("insufficient_funds");
        }
        try {
            activity.activateEngagement(input);
            return ReservationOutcome.accepted();
        } catch (Exception activateFailure) {
            // 预留成功但激活失败 → 释放预留 + 回退报名（幂等补偿）
            activity.compensateAcceptance(input, reserve, "activate_failed");
            return ReservationOutcome.compensated("activate_failed");
        }
    }
}
