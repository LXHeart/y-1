package com.grassland.intelligence.orchestration;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 任务书 #101 C101-21：公众号草稿同步 workflow 启动门面（照 CreationVisualWorkflowStarter）。
 * workflowId = {@code wechat-draft-{同步UUID}}（任务书指定，确定性同 ID 拒绝重复工作流）； 启动失败留
 * pending 行由收养清扫补起。
 */
@Component
public class WechatDraftWorkflowStarter {

	private static final Logger log = LoggerFactory.getLogger(WechatDraftWorkflowStarter.class);

	private final WorkflowClient client;

	public WechatDraftWorkflowStarter(WorkflowClient client) {
		this.client = client;
	}

	public static String workflowId(UUID syncId) {
		return "wechat-draft-" + syncId;
	}

	/** 幂等启动：同 workflowId 已在跑/已完结不重复起（行侧幂等由 requestId 唯一键兜底）。 */
	public void start(UUID syncId) {
		WechatDraftWorkflow stub = client.newWorkflowStub(WechatDraftWorkflow.class, WorkflowOptions.newBuilder()
				.setWorkflowId(workflowId(syncId)).setTaskQueue(WechatDraftWorkflowImpl.TASK_QUEUE)
				.setWorkflowRunTimeout(java.time.Duration.ofMinutes(11))
				.setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
				.build());
		try {
			WorkflowClient.start(stub::run, syncId.toString());
		} catch (io.temporal.client.WorkflowExecutionAlreadyStarted already) {
			log.info("wechat draft workflow already started workflowId={}", workflowId(syncId));
		}
	}

	/** 取消信号（尽力而为——行是真相源，advance 读库决定停止推进）。 */
	public void signalCancel(UUID syncId, String reason) {
		try {
			client.newWorkflowStub(WechatDraftWorkflow.class, workflowId(syncId)).cancel(reason);
		} catch (RuntimeException error) {
			log.debug("wechat draft cancel signal skipped workflowId={} cause={}", workflowId(syncId),
					String.valueOf(error.getMessage()));
		}
	}
}
