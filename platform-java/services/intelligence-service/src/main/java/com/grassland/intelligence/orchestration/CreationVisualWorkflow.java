package com.grassland.intelligence.orchestration;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * 任务书 #101 C101-10（D08）：视觉任务专用 workflow（run/cancel 协议）。 业务与状态在领域服务和数据库；
 * workflow 只负责「推进直到终态」的轮询骨架（activity 读库决定派发，副作用不自动重试）。
 */
@WorkflowInterface
public interface CreationVisualWorkflow {

	@WorkflowMethod
	void run(String operationId);

	@SignalMethod
	void cancel(String reason);
}
