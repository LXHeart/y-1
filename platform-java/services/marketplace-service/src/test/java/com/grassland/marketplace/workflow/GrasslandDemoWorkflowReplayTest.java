package com.grassland.marketplace.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 用 {@link TestWorkflowEnvironment}（replay 引擎）验证 demo workflow 确定性执行：相同输入恒定输出，
 * 且 workflow 实现无非确定性构造（HLD 532 Replay 要求）。纯单元测试，不启动 Spring 上下文。
 */
class GrasslandDemoWorkflowReplayTest {

    private TestWorkflowEnvironment env;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(GrasslandDemoWorkflowImpl.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(GrasslandDemoWorkflowImpl.class);
        worker.registerActivitiesImplementations(new GrasslandDemoActivityImpl());
        env.start();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void runsDeterministicallyWithSameOutput() {
        WorkflowClient client = env.getWorkflowClient();
        GrasslandDemoWorkflow stub = client.newWorkflowStub(
                GrasslandDemoWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(GrasslandDemoWorkflowImpl.TASK_QUEUE)
                        .build());
        // sleepSeconds=0 跳过 Timer 等待，聚焦验证 Activity 串联与确定性。
        String result = stub.run("replay-seed", 0);
        assertThat(result).isEqualTo("prepared:replay-seed|finished");
    }
}
