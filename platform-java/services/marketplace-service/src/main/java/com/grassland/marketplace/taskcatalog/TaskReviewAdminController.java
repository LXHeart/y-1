package com.grassland.marketplace.taskcatalog;

import com.grassland.identity.assertion.BackendRole;
import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 任务内容审核（GL-P2-ADMIN-003 全审政策）—— admin 端点从 {@link TaskController} 提取（任务书 #67
 * Card F）。
 */
@RestController
public class TaskReviewAdminController {

	private final MarketplaceCallerResolver callers;
	private final TaskRepository tasks;
	private final TaskReviewRepository taskReviews;
	private final TaskReviewService taskReviewService;
	private final TaskPublishGate publishGate;
	private final OutboxRepository outbox;
	private final TransactionalOperator transactions;
	private final TaskResourceAuthorization taskAuthorization;

	public TaskReviewAdminController(MarketplaceCallerResolver callers, TaskRepository tasks,
			TaskReviewRepository taskReviews, TaskReviewService taskReviewService, TaskPublishGate publishGate,
			OutboxRepository outbox, TransactionalOperator transactions, TaskResourceAuthorization taskAuthorization) {
		this.callers = callers;
		this.tasks = tasks;
		this.taskReviews = taskReviews;
		this.taskReviewService = taskReviewService;
		this.publishGate = publishGate;
		this.outbox = outbox;
		this.transactions = transactions;
		this.taskAuthorization = taskAuthorization;
	}

	/**
	 * 待审核任务队列（内容审核员视角）。门闩 requireRole(CONTENT_REVIEWER)，PLATFORM_ADMIN 超集。 任务书
	 * #53：data 信封化 {@code {items, total, limit, offset}}；meta 保留 queue 四统计并补 total。
	 */
	@GetMapping("/api/admin/tasks/review")
	public Mono<ResponseEntity<Map<String, Object>>> listPendingReview(
			@org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "50") int limit,
			@org.springframework.web.bind.annotation.RequestParam(required = false) String status,
			@org.springframework.web.bind.annotation.RequestParam(required = false) String organizationId,
			@org.springframework.web.bind.annotation.RequestParam(required = false) String platform,
			@org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "false") boolean overdue,
			@org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "0") int offset,
			@org.springframework.web.bind.annotation.RequestParam(required = false) String q,
			ServerHttpRequest request) {
		String query = searchQuery(q);
		String statusFilter = blankToNull(status);
		int safeLimit = clampLimit(limit);
		int safeOffset = Math.max(0, offset);
		return callers.requireRole(request, BackendRole.CONTENT_REVIEWER)
				.then(Mono.zip(
						tasks.findReviewQueue(statusFilter, blankToNull(organizationId), blankToNull(platform), overdue,
								safeLimit, safeOffset, query).map(TaskReviewAdminController::toBody).collectList(),
						tasks.countReviewQueue(statusFilter, blankToNull(organizationId), blankToNull(platform),
								overdue, query),
						taskReviews.queueStats()))
				.map(tuple -> {
					Map<String, Object> data = new LinkedHashMap<>();
					data.put("items", tuple.getT1());
					data.put("total", tuple.getT2());
					data.put("limit", safeLimit);
					data.put("offset", safeOffset);
					Map<String, Object> meta = new LinkedHashMap<>();
					meta.put("offset", safeOffset);
					meta.put("limit", safeLimit);
					meta.put("total", tuple.getT2());
					var stats = tuple.getT3();
					meta.put("queue",
							Map.of("pending", stats.pending(), "overdue", stats.overdue(), "approvedLast24Hours",
									stats.approvedLast24Hours(), "rejectedLast24Hours", stats.rejectedLast24Hours()));
					Map<String, Object> body = new LinkedHashMap<>();
					body.put("success", true);
					body.put("data", data);
					body.put("meta", meta);
					return ResponseEntity.ok(body);
				});
	}

	/** 任务书 #53 信封钳制：limit ≤0 归默认 50，上限 200；与「默认 50 钳 1–200」一致。 */
	private static int clampLimit(int limit) {
		return limit <= 0 ? 50 : Math.min(limit, 200);
	}

	/** Append-only review history for audit drill-down. */
	@GetMapping("/api/admin/tasks/{id}/review/history")
	public Mono<ResponseEntity<Map<String, Object>>> reviewHistory(@PathVariable String id,
			@org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "100") int limit,
			@org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "0") int offset,
			ServerHttpRequest request) {
		return callers.requireRole(request, BackendRole.CONTENT_REVIEWER)
				.then(tasks.findById(id).switchIfEmpty(Mono.error(new MarketplaceException(404, "任务不存在"))))
				.then(taskReviews.findHistory(id, limit, offset).map(TaskReviewAdminController::reviewBody)
						.collectList())
				.map(items -> ResponseEntity.ok(Map.of("success", true, "data", items, "meta",
						Map.of("offset", Math.max(0, offset), "limit", Math.max(1, Math.min(limit, 200))))));
	}

	@GetMapping("/api/admin/tasks/review/stats")
	public Mono<ResponseEntity<Map<String, Object>>> reviewStats(ServerHttpRequest request) {
		return callers.requireRole(request, BackendRole.CONTENT_REVIEWER).then(taskReviews.queueStats())
				.map(stats -> ResponseEntity.ok(Map.of("success", true, "data",
						Map.of("pending", stats.pending(), "overdue", stats.overdue(), "approvedLast24Hours",
								stats.approvedLast24Hours(), "rejectedLast24Hours", stats.rejectedLast24Hours()))));
	}

	/**
	 * 审核通过（pending_review→published，正式上架）。
	 *
	 * <p>
	 * 闸门 4+5（活跃/月度额度）重跑——审核期间商家可能别处又发了任务导致额度已满。 闸门 2+3（tier/资金）也重跑——审核期间 tier 可能被降。
	 * owner tier 从 task 行的 ownerAccountId + organizationId 反查（审核员不是 merchant）。
	 */
	@PostMapping(value = "/api/admin/tasks/{id}/review/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> reviewApprove(@PathVariable String id,
			@RequestBody TaskLifecycleRequest body, ServerHttpRequest request) {
		return callers.requireRole(request, BackendRole.CONTENT_REVIEWER).flatMap(reviewer -> tasks.findById(id)
				.switchIfEmpty(Mono.error(new MarketplaceException(404, "任务不存在"))).flatMap(task -> {
					if (!TaskStatus.PENDING_REVIEW.dbValue().equals(task.status())) {
						return Mono.<Task>error(new MarketplaceException(409, "该任务不在待审核状态"));
					}
					return taskAuthorization.requireCurrentOwnerManager(task)
							.flatMap(access -> transactions.transactional(tasks
									.acquireOrganizationPublishLock(task.organizationId())
									.then(publishGate.enforce(task.organizationId(), access.permissionTier(),
											task.bountyCents(), task.freebieDepositCents()))
									.then(enforceLadderBudget(task.requirements(), task.bountyCents()))
									.then(tasks.reviewApprove(id, body.expectedVersion(), reviewer.accountId())
											.switchIfEmpty(Mono.error(new MarketplaceException(409, "任务已变更，请刷新后重试")))
											.flatMap(approved -> taskReviews
													.append(id, "approved", reviewer.accountId(), null)
													.then(outbox.append(taskPublishedEnvelope(approved)))
													.thenReturn(approved)))));
				})).map(task -> ResponseEntity.ok(Map.of("success", true, "data", toBody(task))));
	}

	/**
	 * 审核驳回（pending_review→draft，退回让商家修改后重新提交）。
	 */
	@PostMapping(value = "/api/admin/tasks/{id}/review/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> reviewReject(@PathVariable String id,
			@RequestBody TaskReviewRequest body, ServerHttpRequest request) {
		return callers.requireRole(request, BackendRole.CONTENT_REVIEWER).flatMap(reviewer -> {
			String note = body.requireNote();
			return tasks.findById(id).switchIfEmpty(Mono.error(new MarketplaceException(404, "任务不存在")))
					.flatMap(task -> {
						if (!TaskStatus.PENDING_REVIEW.dbValue().equals(task.status())) {
							return Mono.<Task>error(new MarketplaceException(409, "该任务不在待审核状态"));
						}
						return transactions.transactional(tasks.reviewReject(id, body.expectedVersion())
								.switchIfEmpty(Mono.error(new MarketplaceException(409, "任务已变更，请刷新后重试")))
								.flatMap(rejected -> taskReviews.append(id, "rejected", reviewer.accountId(), note)
										.then(outbox.append(taskRejectedEnvelope(rejected, note)))
										.thenReturn(rejected)));
					});
		}).map(task -> ResponseEntity.ok(Map.of("success", true, "data", toBody(task))));
	}

	private Mono<Void> enforceLadderBudget(TaskRequirements requirements, Long bountyCents) {
		if (requirements != null && requirements.commissionLadder() != null) {
			try {
				requirements.commissionLadder().validateReserve(bountyCents);
			} catch (IllegalArgumentException error) {
				return Mono.error(new MarketplaceException(400, error.getMessage()));
			}
		}
		return Mono.empty();
	}

	private EventEnvelope taskPublishedEnvelope(Task task) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("taskId", task.id());
		payload.put("organizationId", task.organizationId());
		payload.put("ownerAccountId", task.ownerAccountId());
		payload.put("title", task.title());
		payload.put("version", task.version());
		if (task.storeId() != null) {
			payload.put("storeId", task.storeId());
		}
		if (task.applicationDeadline() != null) {
			payload.put("applicationDeadline", task.applicationDeadline().toString());
		}
		return new EventEnvelope(UUID.randomUUID().toString(), "TaskPublished", "Task", task.id(), task.version(),
				Instant.now(), null, payload);
	}

	/**
	 * Reviewer rejection is a merchant-facing event; identity resolves only the
	 * owner in this payload.
	 */
	private EventEnvelope taskRejectedEnvelope(Task task, String note) {
		Map<String, Object> payload = taskEventPayload(task, true);
		payload.put("reason", note);
		payload.put("status", "draft");
		return new EventEnvelope(UUID.randomUUID().toString(), "TaskReviewRejected", "Task", task.id(), task.version(),
				Instant.now(), null, payload);
	}

	private static Map<String, Object> taskEventPayload(Task task, boolean includeTitle) {
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

	private static Map<String, Object> toBody(Task task) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", task.id());
		m.put("ownerAccountId", task.ownerAccountId());
		m.put("organizationId", task.organizationId());
		if (task.storeId() != null) {
			m.put("storeId", task.storeId());
		}
		m.put("title", task.title());
		m.put("description", task.description());
		m.put("status", task.status());
		m.put("contentForm", task.contentForm());
		m.put("platform", task.platform());
		m.put("maxSlots", task.maxSlots());
		m.put("bountyCents", task.bountyCents());
		m.put("freebieDepositCents", task.freebieDepositCents());
		// 任务书 #62：仅在有目标问题时出现（缺省不出字段，旧前端零影响）
		if (task.question().present()) {
			m.put("questionText", task.question().text());
			if (task.question().ref() != null) {
				m.put("questionRef", task.question().ref());
			}
		}
		m.put("minRecommenderLevel", task.minRecommenderLevel());
		m.put("requirements", task.requirements());
		// 任务书 #75：治理台任务列表「套餐推广」类型标识（卡 D4）。
		if (task.commercePackageId() != null) {
			m.put("commercePackageId", task.commercePackageId());
		}
		m.put("version", task.version());
		m.put("applicationDeadline", task.applicationDeadline() == null ? null : task.applicationDeadline().toString());
		m.put("autoAcceptMinLevel", task.autoAcceptMinLevel());
		// 任务书 #53：审核视图字段仅 rejected 视图（最新决定为驳回的 draft）有值，其余路径恒 null。
		m.put("lastReviewAction", task.lastReviewAction());
		m.put("lastReviewNote", task.lastReviewNote());
		m.put("lastReviewedAt", task.lastReviewAt() == null ? null : task.lastReviewAt().toString());
		// 商家端驳回回显：仅当任务仍 draft 且最新一条审核记录为 rejected 时非 null，
		// 避免已上架任务泄漏历史驳回（重新提交/通过后不再显示）。
		boolean showRejected = TaskStatus.DRAFT.dbValue().equals(task.status())
				&& "rejected".equals(task.lastReviewAction());
		m.put("lastRejectedNote", showRejected ? task.lastReviewNote() : null);
		m.put("lastRejectedAt", showRejected && task.lastReviewAt() != null ? task.lastReviewAt().toString() : null);
		m.put("publishedAt", task.publishedAt() == null ? null : task.publishedAt().toString());
		m.put("cancelledAt", task.cancelledAt() == null ? null : task.cancelledAt().toString());
		m.put("createdAt", task.createdAt() == null ? null : task.createdAt().toString());
		return m;
	}

	private static Map<String, Object> reviewBody(TaskReviewRepository.TaskReviewEntry entry) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", entry.id());
		body.put("taskId", entry.taskId());
		body.put("action", entry.action());
		body.put("reviewerAccountId", entry.reviewerAccountId());
		body.put("note", entry.note());
		body.put("createdAt", entry.createdAt() == null ? null : entry.createdAt().toString());
		return body;
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value;
	}

	private static String searchQuery(String value) {
		String query = blankToNull(value == null ? null : value.trim());
		if (query == null)
			return null;
		if (query.length() > 100)
			throw new MarketplaceException(400, "q 最长 100 字符");
		return "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
	}
}
