package com.grassland.marketplace.workflow.saga;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * 接受报名资金预留 Saga 的活动集（草场 Epic 4 Slice 4F / HLD 9.2）。每个活动幂等 + 执行前重验状态。
 *
 * <ul>
 *   <li>{@code beginAcceptance} — pending→reserving（重验 owner/pending/名额；写 outbox ApplicationAcceptanceStarted）。</li>
 *   <li>{@code reserveFunds} — 调 finance ReserveFunds（RPC 仅在 activity 内，绝不在 workflow body）。</li>
 *   <li>{@code activateEngagement} — reserving→accepted（重验 reserving；写 outbox ApplicationAccepted）。</li>
 *   <li>{@code compensateAcceptance} — 失败补偿：reserve 成功则 release 退还，再 reserving→pending（写 outbox ApplicationReservationFailed）。</li>
 * </ul>
 */
@ActivityInterface
public interface ApplicationReservationActivity {

    @ActivityMethod
    boolean beginAcceptance(AcceptanceInput input);

    @ActivityMethod
    ReserveResult reserveFunds(AcceptanceInput input);

    @ActivityMethod
    void activateEngagement(AcceptanceInput input);

    @ActivityMethod
    void compensateAcceptance(AcceptanceInput input, ReserveResult reserve, String reason);
}
