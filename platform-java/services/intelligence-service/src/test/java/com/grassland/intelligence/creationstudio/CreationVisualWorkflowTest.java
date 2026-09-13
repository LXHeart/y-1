package com.grassland.intelligence.creationstudio;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.orchestration.CreationVisualActivities;
import com.grassland.intelligence.orchestration.CreationVisualWorkflow;
import com.grassland.intelligence.orchestration.CreationVisualWorkflowImpl;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 任务书 #101 C101-10：CreationVisualWorkflow 骨架重放（TestWorkflowEnvironment + 假
 * activity， 照 VideoProductionWorkflowReplayTest）。确定性约定：假 activity 状态在起流前备好；
 * 虚拟时钟在只剩定时器时自动跳跃。
 */
@DisplayName("Creation visual workflow (replay)")
class CreationVisualWorkflowTest {

	private TestWorkflowEnvironment env;
	private FakeActivities fake;

	@BeforeEach
	void setUp() {
		env = TestWorkflowEnvironment.newInstance();
		fake = new FakeActivities();
	}

	@AfterEach
	void tearDown() {
		env.close();
	}

	private CreationVisualWorkflow stub(String workflowId) {
		return env.getWorkflowClient().newWorkflowStub(CreationVisualWorkflow.class, WorkflowOptions.newBuilder()
				.setWorkflowId(workflowId).setTaskQueue(CreationVisualWorkflowImpl.TASK_QUEUE)
				.setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
				.build());
	}

	private void startWorker() {
		Worker worker = env.newWorker(CreationVisualWorkflowImpl.TASK_QUEUE);
		worker.registerWorkflowImplementationTypes(CreationVisualWorkflowImpl.class);
		worker.registerActivitiesImplementations(fake);
		env.start();
	}

	private void awaitDone(CreationVisualWorkflow stub) {
		WorkflowStub.fromTyped(stub).getResult(Void.class);
	}

	@Test
	@DisplayName("推进到终态后收口：advance false→false→true → workflow 完成（虚拟时钟跳跃）")
	void completesWhenAllItemsTerminal() {
		startWorker();
		fake.terminalAfter.set(3);
		CreationVisualWorkflow workflow = stub("creation-visual-" + UUID.randomUUID());
		WorkflowClient.start(workflow::run, UUID.randomUUID().toString());
		awaitDone(workflow);
		assertThat(fake.calls.get()).isGreaterThanOrEqualTo(3);
	}

	@Test
	@DisplayName("advance 瞬态异常不杀死 workflow：activity 抛错后下一轮读库继续")
	void transientAdvanceFailureRetriedByPolling() {
		startWorker();
		fake.failFirstCalls.set(2);
		fake.terminalAfter.set(4);
		CreationVisualWorkflow workflow = stub("creation-visual-" + UUID.randomUUID());
		WorkflowClient.start(workflow::run, UUID.randomUUID().toString());
		awaitDone(workflow);
		assertThat(fake.calls.get()).isGreaterThanOrEqualTo(4);
		assertThat(fake.failures.get()).isEqualTo(2);
	}

	@Test
	@DisplayName("同 workflowId 幂等：重复启动吞 AlreadyStarted，不产生第二个工作流")
	void sameWorkflowIdStartsOnce() {
		startWorker();
		fake.terminalAfter.set(2);
		String workflowId = "creation-visual-" + UUID.randomUUID();
		CreationVisualWorkflow first = stub(workflowId);
		WorkflowClient.start(first::run, UUID.randomUUID().toString());
		CreationVisualWorkflow second = stub(workflowId);
		boolean alreadyStarted = false;
		try {
			WorkflowClient.start(second::run, UUID.randomUUID().toString());
		} catch (io.temporal.client.WorkflowExecutionAlreadyStarted expected) {
			alreadyStarted = true;
		}
		assertThat(alreadyStarted).as("同 workflowId 重复启动被拒").isTrue();
		awaitDone(first);
	}

	/** 假 activity：前 N 次 false（未终态），之后 true；可注入瞬态失败。 */
	private static final class FakeActivities implements CreationVisualActivities {

		final AtomicInteger calls = new AtomicInteger();
		final AtomicInteger failures = new AtomicInteger();
		final AtomicInteger terminalAfter = new AtomicInteger(Integer.MAX_VALUE);
		final AtomicInteger failFirstCalls = new AtomicInteger(0);

		@Override
		public boolean advance(String operationId) {
			int current = calls.incrementAndGet();
			if (failFirstCalls.get() > 0 && failures.get() < failFirstCalls.get()) {
				failures.incrementAndGet();
				throw new IllegalStateException("transient advance failure");
			}
			// 让瞬态失败后的重试轮次也计入
			return current >= terminalAfter.get() + failures.get();
		}
	}

	@SuppressWarnings("unused")
	private static Duration unusedDuration() {
		return Duration.ofSeconds(2);
	}
}
