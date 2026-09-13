package com.grassland.intelligence.creationstudio;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.orchestration.WechatDraftActivities;
import com.grassland.intelligence.orchestration.WechatDraftWorkflow;
import com.grassland.intelligence.orchestration.WechatDraftWorkflowImpl;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 任务书 #101 C101-21：WechatDraftWorkflow 骨架重放（TestWorkflowEnvironment + 假
 * activity， 照 CreationVisualWorkflowTest）。确定性约定：假 activity 状态在起流前备好；
 * 虚拟时钟在只剩定时器时自动跳跃。 派发边界（持久提交标记）与取消语义由 WechatDraftSyncIT 在数据库层验证。
 */
@DisplayName("Wechat draft workflow (replay)")
class WechatDraftWorkflowTest {

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

	private WechatDraftWorkflow stub(String workflowId) {
		return env.getWorkflowClient().newWorkflowStub(WechatDraftWorkflow.class, WorkflowOptions.newBuilder()
				.setWorkflowId(workflowId).setTaskQueue(WechatDraftWorkflowImpl.TASK_QUEUE)
				.setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
				.build());
	}

	private void startWorker() {
		Worker worker = env.newWorker(WechatDraftWorkflowImpl.TASK_QUEUE);
		worker.registerWorkflowImplementationTypes(WechatDraftWorkflowImpl.class);
		worker.registerActivitiesImplementations(fake);
		env.start();
	}

	private void awaitDone(WechatDraftWorkflow stub) {
		WorkflowStub.fromTyped(stub).getResult(Void.class);
	}

	@Test
	@DisplayName("推进到终态后收口：advance false→false→true → workflow 完成（虚拟时钟跳跃）")
	void completesWhenTerminal() {
		startWorker();
		fake.terminalAfter.set(3);
		WechatDraftWorkflow workflow = stub("wechat-draft-" + UUID.randomUUID());
		WorkflowClient.start(workflow::run, UUID.randomUUID().toString());
		awaitDone(workflow);
		assertThat(fake.calls.get()).isGreaterThanOrEqualTo(3);
	}

	@Test
	@DisplayName("advance 瞬态异常不杀死 workflow：activity 抛错后下一轮读库继续（不重复派发——行是真相源）")
	void transientAdvanceFailureRetriedByPolling() {
		startWorker();
		fake.failFirstCalls.set(2);
		fake.terminalAfter.set(4);
		WechatDraftWorkflow workflow = stub("wechat-draft-" + UUID.randomUUID());
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
		String workflowId = "wechat-draft-" + UUID.randomUUID();
		WechatDraftWorkflow first = stub(workflowId);
		WorkflowClient.start(first::run, UUID.randomUUID().toString());
		WechatDraftWorkflow second = stub(workflowId);
		boolean alreadyStarted = false;
		try {
			WorkflowClient.start(second::run, UUID.randomUUID().toString());
		} catch (io.temporal.client.WorkflowExecutionAlreadyStarted expected) {
			alreadyStarted = true;
		}
		assertThat(alreadyStarted).as("同 workflowId 重复启动被拒").isTrue();
		awaitDone(first);
	}

	@Test
	@DisplayName("取消信号不直接终止骨架：终态由 advance（读库）决定后收口")
	void cancelSignalDoesNotAbortSkeleton() {
		startWorker();
		fake.terminalAfter.set(3);
		String workflowId = "wechat-draft-" + UUID.randomUUID();
		WechatDraftWorkflow workflow = stub(workflowId);
		WorkflowClient.start(workflow::run, UUID.randomUUID().toString());
		// 中途发取消信号：语义权威在 API101-31 与数据库行；骨架只置标志继续轮询
		workflow.cancel("user-cancel");
		awaitDone(workflow);
		assertThat(fake.calls.get()).isGreaterThanOrEqualTo(3);
	}

	/** 假 activity：前 N 次 false（未终态），之后 true；可注入瞬态失败。 */
	private static final class FakeActivities implements WechatDraftActivities {

		final AtomicInteger calls = new AtomicInteger();
		final AtomicInteger failures = new AtomicInteger();
		final AtomicInteger terminalAfter = new AtomicInteger(Integer.MAX_VALUE);
		final AtomicInteger failFirstCalls = new AtomicInteger(0);

		@Override
		public boolean advance(String syncId) {
			int current = calls.incrementAndGet();
			if (failFirstCalls.get() > 0 && failures.get() < failFirstCalls.get()) {
				failures.incrementAndGet();
				throw new IllegalStateException("transient advance failure");
			}
			return current >= terminalAfter.get() + failures.get();
		}
	}
}
