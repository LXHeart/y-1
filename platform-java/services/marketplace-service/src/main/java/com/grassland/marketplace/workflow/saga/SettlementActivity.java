package com.grassland.marketplace.workflow.saga;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * 结算窗口 Saga 的活动（草场 Epic 5 Slice 5A / HLD 9.2、10.3）。窗口到期后执行：重验 accepted+confirmed、
 * 查争议、capture 或 hold。幂等 + 执行前重验状态。
 */
@ActivityInterface
public interface SettlementActivity {

    @ActivityMethod
    SettlementOutcome captureSettlement(SettlementInput input);
}
