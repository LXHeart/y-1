package com.grassland.marketplace.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * 草场 Temporal 平台验证 workflow（Epic 3 Slice 3C）。
 *
 * <p>非业务 workflow——用于验证 Temporal 引擎链路：Activity 调用 + Retry + Timer（{@code Workflow.sleep}）
 * + 确定性 Replay。HLD 业务 workflow（报名预留 / 结算窗口 / 争议裁决等，见
 * {@code docs/草场系统技术总体设计（HLD-v0.1）.md} 第 9.3 节）属 Epic 4-6，待 finance/trust 域落地。
 */
@WorkflowInterface
public interface GrasslandDemoWorkflow {

    /**
     * @param seed 业务种子（透传到 activity，演示参数流转）
     * @param sleepSeconds Timer 等待秒数（演示长流程）
     * @return 聚合后的最终结果
     */
    @WorkflowMethod
    String run(String seed, int sleepSeconds);
}
