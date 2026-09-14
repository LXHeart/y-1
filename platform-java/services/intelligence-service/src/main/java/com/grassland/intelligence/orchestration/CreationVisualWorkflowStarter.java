package com.grassland.intelligence.orchestration;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 任务书 #101 C101-10：视觉 workflow 启动门面（照 VideoWorkflowStarter）。 workflowId =
 * {@code creation-visual-{父操作UUID}}（确定性，同 ID 拒绝重复工作流）；启动失败留 queued 行由收养清扫补起。
 */
@Component
public class CreationVisualWorkflowStarter {

	private static final Logger log = LoggerFactory.getLogger(CreationVisualWorkflowStarter.class);

	private final WorkflowClient client;

	public CreationVisualWorkflowStarter(WorkflowClient client) {
		this.client = client;
	}

	public static String workflowId(UUID operationId) {
		return "creation-visual-" + operationId;
	}

	/** 幂等启动：同 workflowId 已在跑/已完结不重复起（行侧幂等由 requestId 唯一键兜底）。 */
	public void start(UUID operationId) {
		CreationVisualWorkflow stub = client.newWorkflowStub(CreationVisualWorkflow.class, WorkflowOptions.newBuilder()
				.setWorkflowId(workflowId(operationId)).setTaskQueue(CreationVisualWorkflowImpl.TASK_QUEUE)
				.setWorkflowRunTimeout(java.time.Duration.ofMinutes(30))
				.setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
				.build());
		try {
			WorkflowClient.start(stub::run, operationId.toString());
		} catch (io.temporal.client.WorkflowExecutionAlreadyStarted already) {
			log.info("creation visual workflow already started workflowId={}", workflowId(operationId));
		}
	}

	/** 取消信号（尽力而为——行是真相源，activity 自行读取 cancel_requested）。 */
	public void signalCancel(UUID operationId, String reason) {
		try {
			client.newWorkflowStub(CreationVisualWorkflow.class, workflowId(operationId)).cancel(reason);
		} catch (RuntimeException error) {
			log.debug("creation visual cancel signal skipped workflowId={} cause={}", workflowId(operationId),
					String.valueOf(error.getMessage()));
		}
	}
}
