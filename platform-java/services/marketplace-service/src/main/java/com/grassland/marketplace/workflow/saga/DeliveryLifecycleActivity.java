package com.grassland.marketplace.workflow.saga;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * 交付看门狗 Saga 的活动（任务书 #96 C96-01 / D96-02）。幂等 + 执行前重验状态，照
 * {@link ConfirmationActivity} 惯例：确定性 eventId 保重试 exactly-once，跨服务资金调用留事务外。
 */
@ActivityInterface
public interface DeliveryLifecycleActivity {

    /** 临交付截止提醒：发 outbox {@code DeliveryDeadlineExpiring}（双方收件）。 */
    @ActivityMethod
    void notifyDeliveryExpiring(DeliveryDeadlineInput input);

    /** 补救窗到期终结：无交付按推荐官有责终结（资金释放 + 名额回收 + {@code DeliveryTimeoutTerminated}）。 */
    @ActivityMethod
    DeliveryOutcome terminateDeliveryTimeout(DeliveryDeadlineInput input);
}
