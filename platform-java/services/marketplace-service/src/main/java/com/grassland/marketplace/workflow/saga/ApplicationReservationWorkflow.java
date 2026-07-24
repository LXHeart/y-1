package com.grassland.marketplace.workflow.saga;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * 草场第一个业务 Temporal workflow（Epic 4 Slice 4F / HLD 10.2）：商家接受报名 → finance ReserveFunds
 * → 成功激活履约 / 失败补偿名额与报名。
 *
 * <p>编排（{@code marketplace-saga} task-queue）：
 * <pre>
 * beginAcceptance → reserveFunds → { activateEngagement (成功) | compensateAcceptance (失败) }
 * </pre>
 * reserve 成功但 activate 失败 → release + 回退（compensateAcceptance 双分支）。RPC 仅在 activity 内（HLD 9.2 确定性铁律）。
 */
@WorkflowInterface
public interface ApplicationReservationWorkflow {

    @WorkflowMethod
    ReservationOutcome run(AcceptanceInput input);
}
