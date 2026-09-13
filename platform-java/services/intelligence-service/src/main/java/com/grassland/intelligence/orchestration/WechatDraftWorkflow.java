package com.grassland.intelligence.orchestration;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * 任务书 #101 C101-21（§6.8）：公众号草稿同步专用 workflow（run/cancel 协议）。 业务与状态（含提交标记）在
 * 领域服务和数据库；workflow 只负责「推进直到终态」的轮询骨架—— draft/add 的最多一次语义由持久提交标记保证， activity
 * 不复制默认重试副作用。
 */
@WorkflowInterface
public interface WechatDraftWorkflow {

	@WorkflowMethod
	void run(String syncId);

	@SignalMethod
	void cancel(String reason);
}
