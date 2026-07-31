package com.grassland.identity.notification;

/** 通知消费处理结果。镜像 marketplace {@code TrustEventProcessingResult}。 */
public enum NotificationProcessingResult {
    /** 新事件，已写 inbox + 派生通知（或无收件人但 inbox 已记录）。 */
    PROCESSED,
    /** 幂等命中——同 (consumer, event_id) 已处理过。 */
    DUPLICATE,
    /** 非关注事件类型，未写 inbox。 */
    IGNORED
}
