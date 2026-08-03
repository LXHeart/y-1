package com.grassland.marketplace.workflow.saga;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * 商家确认窗口 workflow（D-03 / HLD 10.3、9.2）。
 *
 * <p>编排：{@code Workflow.sleep(确认窗口)} → {@code autoConfirmSettle} activity。推荐官提交履约时由
 * {@code ApplicationController.submitDeliverable} 启动。Timer 到期只触发 autoConfirmSettle Command
 * （HLD 9.2「Timer 到期只触发 Command」），由 activity 重验 + 自动确认 + 结算 capture/hold。
 *
 * <p>窗口内商家手动 {@code confirm} → 设 confirmed_at 并启 {@link SettlementWindowWorkflow}；本 workflow 到期时
 * activity 见 confirmed_at 已设即 abort（两条路径经 finance.capture 幂等 + 确定性 eventId 安全收敛，无双结算）。
 */
@WorkflowInterface
public interface ConfirmationWindowWorkflow {

    @WorkflowMethod
    ConfirmationOutcome run(ConfirmationInput input);
}
