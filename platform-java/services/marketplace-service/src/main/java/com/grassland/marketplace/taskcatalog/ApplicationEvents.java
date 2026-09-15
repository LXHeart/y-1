package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.event.EventEnvelope;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * application 聚合的 outbox 事件信封工厂（Slice 12 Stage 3）。
 *
 * <p>
 * {@code taskOwnerId} 携带任务归属（apply/withdraw/accept/reject 全携带），供 identity 通知中心
 * 解析商家侧收件人；与 {@code recommenderAccountId} 合计覆盖争议双方。confirmation/verification
 * 事件用 确定性 event_id（type-3 UUID），保 outbox 重试 exactly-once。
 */
final class ApplicationEvents {

	private ApplicationEvents() {
	}

	/**
	 * application
	 * 状态事件（ApplicationSubmitted/Accepted/AcceptanceStarted/Rejected/Withdrawn）。
	 */
	static EventEnvelope envelope(String eventType, TaskApplication app, String taskOwnerId) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("taskId", app.taskId());
		payload.put("applicationId", app.id());
		payload.put("recommenderAccountId", app.recommenderAccountId());
		payload.put("status", app.status());
		if (taskOwnerId != null) {
			payload.put("taskOwnerId", taskOwnerId);
		}
		return new EventEnvelope(UUID.randomUUID().toString(), eventType, "TaskApplication", app.id(), 1, Instant.now(),
				null, payload);
	}

	/** 确认窗口事件（ConfirmationWindowEntered）：submissionId 维度确定性 event_id。 */
	static EventEnvelope confirmationEnvelope(String eventType, TaskApplication app, String submissionId,
			String taskOwnerId) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("taskId", app.taskId());
		payload.put("applicationId", app.id());
		payload.put("submissionId", submissionId);
		payload.put("recommenderAccountId", app.recommenderAccountId());
		payload.put("status", app.status());
		if (taskOwnerId != null) {
			payload.put("taskOwnerId", taskOwnerId);
		}
		String eventId = UUID.nameUUIDFromBytes((eventType + ":" + submissionId).getBytes(StandardCharsets.UTF_8))
				.toString();
		return new EventEnvelope(eventId, eventType, "TaskApplication", app.id(), 1, Instant.now(), null, payload);
	}

	/**
	 * 交付物事件：带上 application 与交付物两侧的关键字段（含附件 mediaIds + taskOwnerId），供下游（核实引擎/通知）消费。
	 */
	static EventEnvelope submissionEnvelope(String eventType, TaskApplication app, EngagementSubmission submission,
			List<AttachmentInput> attachments, String taskOwnerId, String organizationId) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("taskId", app.taskId());
		payload.put("applicationId", app.id());
		payload.put("recommenderAccountId", app.recommenderAccountId());
		payload.put("submissionId", submission.id());
		payload.put("contentUrl", submission.contentUrl());
		payload.put("status", submission.status());
		if (taskOwnerId != null) {
			payload.put("taskOwnerId", taskOwnerId);
		}
		// 任务书 #103 C103-13（§6.4）：履约矩阵事件的 M 侧兜底与 O 排除所需组织键。
		if (organizationId != null) {
			payload.put("organizationId", organizationId);
		}
		if (!attachments.isEmpty()) {
			payload.put("mediaIds", attachments.stream().map(a -> a.mediaId().toString()).toList());
		}
		return new EventEnvelope(UUID.randomUUID().toString(), eventType, "EngagementSubmission", submission.id(), 1,
				Instant.now(), null, payload);
	}

	/**
	 * 任务书 #103 C103-13（§6.4）：无责退出事件信封——M+R 双侧收件 + O（操作推荐官）排除所需的
	 * organizationId/operatorAccountId；系统动作 operatorAccountId 传 null。
	 */
	static EventEnvelope noFaultExitEnvelope(TaskApplication app, Task task, String operatorAccountId) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("taskId", task.id());
		payload.put("applicationId", app.id());
		payload.put("recommenderAccountId", app.recommenderAccountId());
		payload.put("taskOwnerId", task.ownerAccountId());
		payload.put("organizationId", task.organizationId());
		payload.put("exitedAt", app.exitedAt() == null ? null : app.exitedAt().toString());
		payload.put("exitKind", app.exitKind());
		if (operatorAccountId != null) {
			payload.put("operatorAccountId", operatorAccountId);
		}
		return new EventEnvelope(UUID.randomUUID().toString(), "ApplicationExitedNoFault", "TaskApplication", app.id(),
				1, Instant.now(), null, payload);
	}

	/** 评分事件：带上被评人与分数，供下游（声誉/风控）消费。 */
	static EventEnvelope ratingEnvelope(TaskApplication app, EngagementRating rating) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("taskId", app.taskId());
		payload.put("applicationId", app.id());
		payload.put("recommenderAccountId", rating.recommenderAccountId());
		payload.put("ratedByAccountId", rating.ratedByAccountId());
		payload.put("score", rating.score());
		return new EventEnvelope(UUID.randomUUID().toString(), "EngagementRated", "EngagementRating", rating.id(), 1,
				Instant.now(), null, payload);
	}
}
