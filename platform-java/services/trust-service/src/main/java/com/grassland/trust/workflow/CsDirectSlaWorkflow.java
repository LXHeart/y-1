package com.grassland.trust.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * 任务书 #74 卡 A：客服直裁 SLA workflow（D6：5 天内处理完成）。
 *
 * <p>cs_direct 争议开案即启（固定 workflowId {@code cs-direct-sla-{disputeId}}）：
 * {@code sleep(slaSeconds)} 到点后调用 activity 自动终局——客服已提前终裁则幂等 no-op。
 * 默认裁决 = 维持系统核实结果 {@code for_recommender}（照 merchant_rejection auto-finalize 语义）。
 * 替代「到期扫描」——无调度基建，不新增内部端点滥用（任务书卡 A 改动点 5 定死方案）。
 */
@WorkflowInterface
public interface CsDirectSlaWorkflow {

    @WorkflowMethod
    void run(CsDirectSlaInput input);
}
