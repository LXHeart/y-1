package com.grassland.marketplace.taskcatalog;

import com.grassland.identity.assertion.BackendRole;
import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 退出资金操作端点（任务书 #103 C103-03 / §6.2）。
 *
 * <ul>
 *   <li>{@code GET /api/tasks/{id}/applications/{appId}/exit-funds}：本人推荐官或该任务 manager
 *       范围可查；其他用户不可查询。无操作 → data=null；不暴露租约与原始 Finance 回包。</li>
 *   <li>{@code GET /api/admin/engagement-exit-operations}：FINANCE/RISK 可读队列（keyset 游标）。</li>
 *   <li>{@code POST /api/admin/engagement-exit-operations/{operationId}/retry}：仅 FINANCE；
 *       reason + expectedVersion，只排队原键；已成功回读 200；版本/资金冲突 409。</li>
 * </ul>
 */
@RestController
public class EngagementExitOperationController {

	private final TaskApplicationRepository apps;
	private final TaskRepository tasks;
	private final TaskResourceAuthorization taskAuthorization;
	private final EngagementExitOperationRepository operations;
	private final EngagementExitFundsService funds;
	private final MarketplaceCallerResolver callers;

	public EngagementExitOperationController(TaskApplicationRepository apps, TaskRepository tasks,
			TaskResourceAuthorization taskAuthorization, EngagementExitOperationRepository operations,
			EngagementExitFundsService funds, MarketplaceCallerResolver callers) {
		this.apps = apps;
		this.tasks = tasks;
		this.taskAuthorization = taskAuthorization;
		this.operations = operations;
		this.funds = funds;
		this.callers = callers;
	}

	@GetMapping("/api/tasks/{id}/applications/{appId}/exit-funds")
	public Mono<ResponseEntity<Map<String, Object>>> exitFunds(@PathVariable String id, @PathVariable String appId,
			ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> apps.findById(appId)
				.switchIfEmpty(fail(404, "报名不存在")).flatMap(app -> {
					if (!app.taskId().equals(id)) {
						return fail(404, "报名不存在");
					}
					return tasks.findById(id).switchIfEmpty(fail(404, "任务不存在")).<Boolean>flatMap(task -> {
						if (app.recommenderAccountId().equals(caller.accountId())) {
							return Mono.just(true);
						}
						// 非本人：仅该任务组织/门店 manager 范围可查（推荐官同伴与无关用户不可见）。
						return taskAuthorization.requireScope(caller, task.organizationId(), task.storeId(), "manager")
								.thenReturn(true);
					}).flatMap(authorized -> operations.findByApplication(appId)
							.map(EngagementExitOperationController::fundsView)
							.defaultIfEmpty(notOperating()));
				}).map(data -> ResponseEntity.ok(Map.of("success", true, "data", data))));
	}

	@GetMapping("/api/admin/engagement-exit-operations")
	public Mono<ResponseEntity<Map<String, Object>>> adminList(@RequestParam(required = false) String state,
			@RequestParam(required = false) String applicationId, @RequestParam(required = false) String cursor,
			@RequestParam(required = false, defaultValue = "50") int limit, ServerHttpRequest request) {
		return callers.requireRole(request, BackendRole.FINANCE, BackendRole.RISK)
				.thenMany(operations.findPage(state, applicationId, cursor, limit))
				.collectList()
				.map(items -> {
					Map<String, Object> data = new LinkedHashMap<>();
					data.put("items", items.stream().map(EngagementExitOperationController::adminView).toList());
					data.put("nextCursor", items.size() >= Math.max(1, Math.min(100, limit)) && !items.isEmpty()
							? items.get(items.size() - 1).updatedAt() + "|" + items.get(items.size() - 1).id()
							: null);
					return ResponseEntity.ok(Map.of("success", true, "data", data));
				});
	}

	/** 运营重排：只排队原经济键；不接受金额或新收款方（载荷仅 reason/expectedVersion）。 */
	@PostMapping("/api/admin/engagement-exit-operations/{operationId}/retry")
	public Mono<ResponseEntity<Map<String, Object>>> retry(@PathVariable String operationId,
			@RequestBody RetryRequest body, ServerHttpRequest request) {
		if (body == null || body.reason() == null || body.reason().isBlank()) {
			return fail(400, "重排必须填写理由");
		}
		String reason = body.reason().trim();
		if (reason.length() < 5 || reason.length() > 500) {
			return fail(400, "理由长度须在 5 到 500 字之间");
		}
		return callers.requireRole(request, BackendRole.FINANCE)
				.then(operations.findById(operationId).switchIfEmpty(fail(404, "退出资金操作不存在"))
						.flatMap(op -> op.succeeded()
								? Mono.just(op)
								: funds.requeue(operationId, body.expectedVersion(), reason)
										.switchIfEmpty(Mono.error(new MarketplaceException(409, "版本或资金状态冲突，请刷新后重试")))))
				.map(op -> ResponseEntity.status(op.succeeded() ? 200 : 202)
						.body(Map.of("success", true, "data", adminView(op))));
	}

	private static Map<String, Object> notOperating() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("operationId", null);
		m.put("state", null);
		return m;
	}

	/** 当事人读模型（§6.2）：不含租约/堆栈/原始回包。 */
	private static Map<String, Object> fundsView(EngagementExitOperation op) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("operationId", op.id());
		m.put("kind", op.kind());
		m.put("state", op.state());
		Map<String, Object> amounts = new LinkedHashMap<>();
		amounts.put("depositRefundCents", legAmount(op, "deposit_refund"));
		amounts.put("bountyCaptureCents", legAmount(op, "bounty_capture"));
		amounts.put("bountyReleaseCents", legAmount(op, "bounty_release"));
		m.put("amounts", amounts);
		m.put("blockedReason", switch (op.state()) {
			case "pending", "processing", "retry_wait" -> "funds_pending";
			case "needs_review" -> "funds_reconciliation_required";
			default -> null;
		});
		m.put("updatedAt", op.updatedAt() == null ? null : op.updatedAt().toString());
		m.put("nextAttemptAt", op.nextAttemptAt() == null ? null : op.nextAttemptAt().toString());
		return m;
	}

	/** 治理台摘要：操作事实 + 账务引用 + 重试次数；不含个人正文。 */
	private static Map<String, Object> adminView(EngagementExitOperation op) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("operationId", op.id());
		m.put("applicationId", op.applicationId());
		m.put("taskId", op.taskId());
		m.put("kind", op.kind());
		m.put("state", op.state());
		m.put("attempts", op.attempts());
		m.put("version", op.version());
		m.put("lastErrorCode", op.lastErrorCode());
		m.put("nextAttemptAt", op.nextAttemptAt() == null ? null : op.nextAttemptAt().toString());
		m.put("updatedAt", op.updatedAt() == null ? null : op.updatedAt().toString());
		m.put("completedAt", op.completedAt() == null ? null : op.completedAt().toString());
		m.put("legs", op.legs().stream().map(leg -> {
			Map<String, Object> l = new LinkedHashMap<>();
			l.put("legKind", leg.legKind());
			l.put("economicKey", leg.economicKey());
			l.put("amountCents", leg.amountCents());
			l.put("state", leg.state());
			l.put("financeReference", leg.financeReference());
			return l;
		}).toList());
		return m;
	}

	private static long legAmount(EngagementExitOperation op, String legKind) {
		return op.legs().stream().filter(l -> l.legKind().equals(legKind)).findFirst()
				.map(EngagementExitOperation.EngagementExitFundLeg::amountCents).orElse(0L);
	}

	private static <T> Mono<T> fail(int status, String message) {
		return Mono.error(new MarketplaceException(status, message));
	}

	record RetryRequest(String reason, long expectedVersion) {
	}
}
