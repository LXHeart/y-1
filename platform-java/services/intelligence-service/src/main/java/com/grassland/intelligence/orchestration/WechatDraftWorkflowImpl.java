package com.grassland.intelligence.orchestration;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 任务书 #101 C101-21：公众号草稿同步 workflow 实现。 轮询骨架：advance 读库推进（压缩/上传/draft/add
 * 派发/回读核实都在领域服务），未终态 sleep 后再推进；终态即收口。取消信号只置标志——取消的权威语义在
 * API101-31（行是真相源）。advance 重试仅覆盖瞬态基础设施错误； draft/add 不因超时/断线/5xx/解析失败自动重试。
 */
@io.temporal.spring.boot.WorkflowImpl(taskQueues = WechatDraftWorkflowImpl.TASK_QUEUE)
public class WechatDraftWorkflowImpl implements WechatDraftWorkflow {

	private static final Logger log = LoggerFactory.getLogger(WechatDraftWorkflowImpl.class);

	public static final String TASK_QUEUE = "intelligence-wechat-draft";

	private final WechatDraftActivities activities = Workflow
			.newActivityStub(WechatDraftActivities.class,
					ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(11)) // 同步总时限 10min + 余量
							.setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3)
									.setInitialInterval(Duration.ofSeconds(2))
									.setDoNotRetry("STUDIO_UNKNOWN_OUTCOME", "STUDIO_CHANNEL_CONTENT_MISMATCH").build())
							.build());

	private boolean cancelRequested;

	@Override
	public void run(String syncId) {
		long pollIntervalMs = 2000;
		while (true) {
			boolean done;
			try {
				done = activities.advance(syncId);
			} catch (RuntimeException error) {
				// advance 失败（瞬态）：短暂退避后重读数据库状态继续；行仍是真相源
				log.warn("wechat draft advance failed syncId={}", syncId, error);
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
