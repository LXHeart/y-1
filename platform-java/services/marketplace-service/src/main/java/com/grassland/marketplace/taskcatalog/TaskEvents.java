package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.event.EventEnvelope;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Task lifecycle event payloads and deterministic cancellation event
 * identities.
 */
public final class TaskEvents {
	private TaskEvents() {
	}

	static EventEnvelope taskPromotionEndedEnvelope(Task task) {
		Map<String, Object> payload = taskEventPayload(task, false);
		return new EventEnvelope(UUID.randomUUID().toString(), "TaskPromotionEnded", "Task", task.id(), task.version(),
				Instant.now(), null, payload);
	}

	static EventEnvelope taskResubmittedEnvelope(Task task) {
		Map<String, Object> payload = taskEventPayload(task, false);
		payload.put("resubmittedFrom", "revise");
		return new EventEnvelope(UUID.randomUUID().toString(), "TaskSubmittedForReview", "Task", task.id(),
				task.version(), Instant.now(), null, payload);
	}

	static EventEnvelope taskDraftUpdatedEnvelope(Task task) {
		return new EventEnvelope(UUID.randomUUID().toString(), "TaskDraftUpdated", "Task", task.id(), task.version(),
				Instant.now(), null, taskEventPayload(task, false));
	}

	static EventEnvelope taskClosedEnvelope(Task task) {
		return TaskFullAutoCloser.taskClosedEnvelope(task, "manual");
	}

	static EventEnvelope taskCancelledEnvelope(Task task) {
		return new EventEnvelope(UUID.randomUUID().toString(), "TaskCancelled", "Task", task.id(), task.version(),
				Instant.now(), null, taskEventPayload(task, false));
	}

	static EventEnvelope engagementRefundedEnvelope(Task task, TaskApplication app) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("taskId", task.id());
		payload.put("applicationId", app.id());
		payload.put("organizationId", task.organizationId());
		payload.put("recommenderAccountId", app.recommenderAccountId());
		payload.put("taskOwnerId", task.ownerAccountId());
		payload.put("reason", "merchant_cancel");
		payload.put("refundDirection",
				app.freebieDepositCents() > 0 && app.bountyCents() > 0
						? "both"
						: app.freebieDepositCents() > 0 ? "recommender" : "merchant");
		String eventId = UUID
				.nameUUIDFromBytes(("EngagementRefundedOnCancel:" + app.id()).getBytes(StandardCharsets.UTF_8))
				.toString();
		return new EventEnvelope(eventId, "EngagementRefundedOnCancel", "TaskApplication", app.id(), 1, Instant.now(),
				null, payload);
	}

	static EventEnvelope cancelledWithSettlementEnvelope(Task task, TaskApplication app,
			com.grassland.marketplace.milestone.EngagementMilestoneService.SettlementBreakdown breakdown) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("taskId", task.id());
		payload.put("applicationId", app.id());
		payload.put("organizationId", task.organizationId());
		payload.put("recommenderAccountId", app.recommenderAccountId());
		payload.put("taskOwnerId", task.ownerAccountId());
		payload.put("reason", "merchant_cancel_with_milestones");
		payload.put("exitKind", "merchant_cancel");
		payload.put("settlement", breakdown.toBody());
		String eventId = UUID
				.nameUUIDFromBytes(("EngagementCancelledWithSettlement:" + app.id()).getBytes(StandardCharsets.UTF_8))
				.toString();
		return new EventEnvelope(eventId, "EngagementCancelledWithSettlement", "TaskApplication", app.id(), 1,
				Instant.now(), null, payload);
	}

	static EventEnvelope taskRevisedEnvelope(Task task) {
		return new EventEnvelope(UUID.randomUUID().toString(), "TaskRevised", "Task", task.id(), task.version(),
				Instant.now(), null, taskEventPayload(task, false));
	}

	static Map<String, Object> taskEventPayload(Task task, boolean includeTitle) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("taskId", task.id());
		payload.put("organizationId", task.organizationId());
		payload.put("ownerAccountId", task.ownerAccountId());
		payload.put("version", task.version());
		if (task.storeId() != null) {
			payload.put("storeId", task.storeId());
		}
		if (includeTitle) {
			payload.put("title", task.title());
		}
		return payload;
	}

}
