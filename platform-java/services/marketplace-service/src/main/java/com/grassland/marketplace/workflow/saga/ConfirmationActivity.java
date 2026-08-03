package com.grassland.marketplace.workflow.saga;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * 商家确认窗口 Saga 的活动（D-03 / HLD 9.2、10.3）。窗口到期后执行：重验 accepted、自动确认（设 confirmed_at）、
 * 结算 capture/hold。幂等 + 执行前重验状态。
 *
 * <p>slice 2 加 {@code notifyExpiring}：窗口中段（临到期 lead 秒）发 {@code ConfirmationWindowExpiring} 提醒，
 * 双方收件（identity 3-touch）。确定性 eventId 保证 activity 重试 exactly-once（不重复通知）。
 */
@ActivityInterface
public interface ConfirmationActivity {

    @ActivityMethod
    ConfirmationOutcome autoConfirmSettle(ConfirmationInput input);

    /** 临到期提醒（D-03 §1「剩余 24h」强提醒）：发 outbox {@code ConfirmationWindowExpiring}。幂等。 */
    @ActivityMethod
    void notifyExpiring(ConfirmationInput input);
}
