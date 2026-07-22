package com.grassland.marketplace.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * demo workflow 实现：prepare activity（带 RetryOptions）→ {@code Workflow.sleep}（Timer）→ finish activity。
 *
 * <p>确定性铁律（HLD 9.2）：workflow 内禁用 {@code Thread.sleep}/{@code Instant.now}/直接 RPC，
 * 必须用 {@code Workflow.*} API 以保证可 replay。
 */
// 注：workflow 实现类不加 @Component——Temporal Worker 在 workflow 线程实例化它（每次执行 new 一个），
// @WorkflowImpl 仅作为 starter auto-discovery 的 marker 把该类注册到 worker。若成 Spring 单例会在
// 非 workflow 线程实例化，导致字段初始化里 Workflow.newActivityStub 抛 "non workflow thread" Error。
@WorkflowImpl(taskQueues = GrasslandDemoWorkflowImpl.TASK_QUEUE)
public class GrasslandDemoWorkflowImpl implements GrasslandDemoWorkflow {

    static final String TASK_QUEUE = "grassland-demo";

    // Activity 调用选项：StartToClose 超时 + 重试（演示 HLD 532 Retry 要求）。
    private final GrasslandDemoActivity activity = Workflow.newActivityStub(
            GrasslandDemoActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(10))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofMillis(200))
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    @Override
    public String run(String seed, int sleepSeconds) {
        String prepared = activity.prepare(seed);
        // Timer：用 Workflow.sleep 保证确定性 replay（禁用 Thread.sleep）。
        Workflow.sleep(Duration.ofSeconds(Math.max(0, sleepSeconds)));
        return activity.finish(prepared);
    }
}
