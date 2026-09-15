package com.grassland.identity.notification;

/**
 * 通知消费处理结果（任务书 #103 C103-14：可观测失败分类，指标按 outcome 拆开）。 镜像 marketplace
 * {@code TrustEventProcessingResult} 并扩展履约矩阵语义。
 */
public enum NotificationProcessingResult {
	/** 新事件，已写 inbox + 派生通知。 */
	PROCESSED,
	/** 幂等命中——同 (consumer, event_id) 已处理过（at-least-once 重投被唯一键吸收）。 */
	DUPLICATE,
	/** 非关注事件类型，未写 inbox（intentionally_ignored——策略性忽略，可观测）。 */
	IGNORED,
	/** 契约错误（信封缺字段/坏 JSON/payload 不可规范化）——不可重试，进 DLT。 */
	CONTRACT_REJECTED,
	/** 矩阵事件无合法收件人（inbox 已记录，不扩大收件人；与「无邮箱」不同）。 */
	RECIPIENT_UNAVAILABLE
}
