package com.grassland.intelligence.orchestration;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 任务书 #101 C101-10：视觉 workflow 实现。
 *
 * <p>
 * 轮询骨架：advance activity 读库派发（封面先行/并发 ≤2/锚点门控都在领域服务）， 未终态则 sleep 后再推进；
 * 终态（succeeded/partial/failed/cancelled/unknown）即收口。取消信号只置标志—— activity 从数据库读取
 * cancel_requested 决定停止派发（行是真相源）。 业务模型调用不采用 Temporal 默认自动重试（§6.6）： advance
 * 重试仅覆盖瞬态基础设施错误，图像派发的最多一次语义由数据库阶段守卫保证。
 */
public class CreationVisualWorkflowImpl implements CreationVisualWorkflow {

	private static final Logger log = LoggerFactory.getLogger(CreationVisualWorkflowImpl.class);

	public static final String TASK_QUEUE = "intelligence-creation-visual";

	private final CreationVisualActivities activities = Workflow
			.newActivityStub(CreationVisualActivities.class,
					ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(35)) // 视觉 workflow 总时限 30min
																								// + 余量
							.setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3)
									.setInitialInterval(Duration.ofSeconds(2))
									.setDoNotRetry("STUDIO_UNKNOWN_OUTCOME", "STUDIO_QUOTE_EXPIRED").build())
							.build());

	private boolean cancelRequested;

	@Override
	public void run(String operationId) {
		long pollIntervalMs = 2000;
		while (true) {
			boolean done;
			try {
				done = activities.advance(operationId);
			} catch (RuntimeException error) {
				// advance 失败（瞬态）：短暂退避后重读数据库状态继续；行仍是真相源
				log.warn("creation visual advance failed operationId={}", operationId, error);
				done = false;
			}
			if (done) {
				return;
			}
			Workflow.sleep(Duration.ofMillis(pollIntervalMs));
			pollIntervalMs = Math.min(pollIntervalMs + 1000, 5000);
		}
	}

	@Override
	public void cancel(String reason) {
		this.cancelRequested = true;
	}

	boolean isCancelRequested() {
		return cancelRequested;
	}
}
