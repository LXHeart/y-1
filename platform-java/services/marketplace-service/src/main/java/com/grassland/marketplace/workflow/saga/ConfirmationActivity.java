package com.grassland.marketplace.workflow.saga;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * 商家确认窗口 Saga 的活动（D-03 / HLD 9.2、10.3）。窗口到期后执行：重验 accepted、自动确认（设 confirmed_at）、
 * 结算 capture/hold。幂等 + 执行前重验状态。
 */
@ActivityInterface
public interface ConfirmationActivity {

    @ActivityMethod
    ConfirmationOutcome autoConfirmSettle(ConfirmationInput input);
}
