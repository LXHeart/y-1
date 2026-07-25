package com.grassland.marketplace.workflow.saga;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * 结算窗口 workflow（草场 Epic 5 Slice 5A / HLD 10.3、9.2）。
 *
 * <p>编排：{@code Workflow.sleep(窗口)} → {@code captureSettlement} activity。Timer 到期只触发 captureSettlement
 * Command（HLD 9.2「Timer 到期只触发 Command，不直接结算」），由 activity 重验+查争议+capture/hold。
 * 商家 {@code ConfirmEngagement}（controller 同步设 confirmed_at + 启本 workflow）触发。
 */
@WorkflowInterface
public interface SettlementWindowWorkflow {

    @WorkflowMethod
    SettlementOutcome run(SettlementInput input);
}
