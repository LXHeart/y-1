package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 合作（engagement）生命周期 HTTP 入口（任务书 #103 C103-21 自 TaskController 拆出）：
 * 无责/协商退出（发起/确认/拒绝/撤回/列表）、交付延期、体验权益（查看/动作/失约主张）、里程碑确认。
 *
 * <p>
 * 端点自 {@code TaskController} <b>原样搬移</b>（路径/守卫/响应不变——路由只保留这一处映射）；
 * 共用的小助手（fail/loadManageableTask）按原实现复制，不改变任何行为。领域编排仍在
 * {@link ApplicationLifecycleService} /
 * {@link com.grassland.marketplace.benefit.ExperienceBenefitService} /
 * {@link com.grassland.marketplace.milestone.EngagementMilestoneService}。
 */
@RestController
public class EngagementLifecycleController {

	private final MarketplaceCallerResolver callers;
	private final TaskRepository tasks;
	private final TaskApplicationRepository apps;
	private final TaskResourceAuthorization taskAuthorization;
	private final ApplicationLifecycleService lifecycle;
	private final com.grassland.marketplace.benefit.ExperienceBenefitService benefitService;
	private final com.grassland.marketplace.milestone.EngagementMilestoneService milestoneService;

	public EngagementLifecycleController(MarketplaceCallerResolver callers, TaskRepository tasks,
			TaskApplicationRepository apps, TaskResourceAuthorization taskAuthorization,
			ApplicationLifecycleService lifecycle,
			com.grassland.marketplace.benefit.ExperienceBenefitService benefitService,
			com.grassland.marketplace.milestone.EngagementMilestoneService milestoneService) {
		this.callers = callers;
		this.tasks = tasks;
		this.apps = apps;
		this.taskAuthorization = taskAuthorization;
		this.lifecycle = lifecycle;
		this.benefitService = benefitService;
		this.milestoneService = milestoneService;
	}

	@PostMapping("/api/tasks/{id}/applications/{appId}/exit")
	public Mono<ResponseEntity<Map<String, Object>>> exit(@PathVariable String id, @PathVariable String appId,
			@RequestBody(required = false) ApplicationExitRequest body, ServerHttpRequest request) {
		String kind = body == null ? ApplicationExitRequest.KIND_NO_FAULT : body.kindOrDefault();
		if (!ApplicationExitRequest.KIND_NO_FAULT.equals(kind)
				&& !ApplicationExitRequest.KIND_NEGOTIATED.equals(kind)) {
			return fail(400, "kind 必须是 no_fault 或 negotiated");
		}
		if (ApplicationExitRequest.KIND_NEGOTIATED.equals(kind)) {
			return negotiateExit(id, appId, body, request);
		}
		return callers.requireRecommender(request)
				.flatMap(rec -> apps.findById(appId).switchIfEmpty(fail(404, "报名不存在")).flatMap(app -> {
					if (!app.taskId().equals(id)) {
						return fail(404, "报名不存在");
					}
					if (!app.recommenderAccountId().equals(rec.accountId())) {
						return fail(403, "无权操作他人报名");
					}
					return tasks.findById(id).switchIfEmpty(fail(404, "任务不存在"))
							// 套餐推广按订单结算，无内容交付期，不适用内容退出（与 submit 同口径）。
							.filter(task -> !task.isCommercePromotion()).switchIfEmpty(fail(409, "套餐推广按订单结算，无需退出履约"))
							.flatMap(task -> lifecycle.exitNoFault(task, app, rec));
				}).map(app -> ResponseEntity.ok(Map.of("success", true, "data", ApplicationBodies.toBody(app)))));
	}

	/**
	 * 协商退出申请发起（任务书 #97 §6）：返回 {exitRequestId, status:'pending', respondDeadlineAt}。
	 */
	private Mono<ResponseEntity<Map<String, Object>>> negotiateExit(String id, String appId,
			ApplicationExitRequest body, ServerHttpRequest request) {
		if (body == null || body.reason() == null || body.reason().isBlank()) {
			return fail(400, "协商退出须填写原因（reason 必填）");
		}
		return callers.requireUser(request)
				.flatMap(caller -> apps.findById(appId).switchIfEmpty(fail(404, "报名不存在")).flatMap(app -> {
					if (!app.taskId().equals(id)) {
						return fail(404, "报名不存在");
					}
					return tasks.findById(id).switchIfEmpty(fail(404, "任务不存在"))
							.flatMap(task -> resolveEngagementParty(task, app, caller).flatMap(
									role -> lifecycle.requestNegotiatedExit(task, app, caller, role, body.reason())));
				}))
				.map(created -> ResponseEntity.status(201)
						.body(Map.of("success", true, "data", Map.of("exitRequestId", created.id(), "status",
								created.status(), "respondDeadlineAt", created.respondDeadlineAt().toString()))));
	}

	/**
	 * 协商退出双方身份裁决（任务书 #97 §5.2 对等）：推荐官限本人报名（role=recommender）；
	 * 商家限本组织任务（taskAuthorization requireScope manager，role=merchant）；均不满足 → 403。
	 */
	private Mono<String> resolveEngagementParty(Task task, TaskApplication app,
			com.grassland.marketplace.security.MarketplaceCallerResolver.Caller caller) {
		if (app.recommenderAccountId().equals(caller.accountId())) {
			return Mono.just("recommender");
		}
		return taskAuthorization.requireScope(caller, task.organizationId(), task.storeId(), "manager")
				.thenReturn("merchant");
	}

	/** 任务行 + 报名行 + 调用方业务方装载（exit-requests 三端点共用守卫：任务/报名存在、报名属该任务、caller 是任一方）。 */
	private record EngagementParty(Task task, TaskApplication app,
			com.grassland.marketplace.security.MarketplaceCallerResolver.Caller caller, String party) {
	}

	private Mono<EngagementParty> loadEngagementForParty(String id, String appId, ServerHttpRequest request) {
		return callers.requireUser(request)
				.flatMap(caller -> apps.findById(appId).switchIfEmpty(fail(404, "报名不存在")).flatMap(app -> {
					if (!app.taskId().equals(id)) {
						return fail(404, "报名不存在");
					}
					return tasks.findById(id).switchIfEmpty(fail(404, "任务不存在"))
							.flatMap(task -> resolveEngagementParty(task, app, caller)
									.map(party -> new EngagementParty(task, app, caller, party)));
				}));
	}

	/** 对方确认协商退出（§6 /exit-requests/{exitId}/confirm）：按已确认里程碑+取消条款部分结算并终态化。 */
	@PostMapping("/api/tasks/{id}/applications/{appId}/exit-requests/{exitId}/confirm")
	public Mono<ResponseEntity<Map<String, Object>>> confirmExitRequest(@PathVariable String id,
			@PathVariable String appId, @PathVariable String exitId, ServerHttpRequest request) {
		return loadEngagementForParty(id, appId, request)
				.flatMap(loaded -> lifecycle.respondNegotiatedExit(loaded.task(), loaded.app(), exitId, loaded.caller(),
						loaded.party(), true))
				.map(confirmed -> ResponseEntity.ok(Map.of("success", true, "data", exitRequestBody(confirmed))));
	}

	/** 对方拒绝协商退出（§6 /exit-requests/{exitId}/reject）：申请关闭，合作按原履约继续。 */
	@PostMapping("/api/tasks/{id}/applications/{appId}/exit-requests/{exitId}/reject")
	public Mono<ResponseEntity<Map<String, Object>>> rejectExitRequest(@PathVariable String id,
			@PathVariable String appId, @PathVariable String exitId, ServerHttpRequest request) {
		return loadEngagementForParty(id, appId, request)
				.flatMap(loaded -> lifecycle.respondNegotiatedExit(loaded.task(), loaded.app(), exitId, loaded.caller(),
						loaded.party(), false))
				.map(rejected -> ResponseEntity.ok(Map.of("success", true, "data", exitRequestBody(rejected))));
	}

	/** 发起方撤回 pending 申请（§6 /exit-requests/{exitId}/cancel）：仅发起方，pending 可撤。 */
	@PostMapping("/api/tasks/{id}/applications/{appId}/exit-requests/{exitId}/cancel")
	public Mono<ResponseEntity<Map<String, Object>>> cancelExitRequest(@PathVariable String id,
			@PathVariable String appId, @PathVariable String exitId, ServerHttpRequest request) {
		return loadEngagementForParty(id, appId, request)
				.flatMap(loaded -> lifecycle.cancelNegotiatedExit(loaded.task(), loaded.app(), exitId, loaded.caller()))
				.map(cancelled -> ResponseEntity.ok(Map.of("success", true, "data", exitRequestBody(cancelled))));
	}

	/** 双方查自己的协商退出申请列表（§6 GET /exit-requests，含服务端预演结算金额）。 */
	@GetMapping("/api/tasks/{id}/applications/{appId}/exit-requests")
	public Mono<ResponseEntity<Map<String, Object>>> listExitRequests(@PathVariable String id,
			@PathVariable String appId, ServerHttpRequest request) {
		return loadEngagementForParty(id, appId, request)
				.flatMap(loaded -> lifecycle.listNegotiatedExits(loaded.task(), loaded.app()))
				.map(items -> ResponseEntity.ok(Map.of("success", true, "data", items)));
	}

	private static Map<String, Object> exitRequestBody(EngagementExitRequestRepository.EngagementExitRequest request) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", request.id());
		body.put("applicationId", request.applicationId());
		body.put("status", request.status());
		body.put("respondDeadlineAt", request.respondDeadlineAt().toString());
		if (request.respondedAt() != null) {
			body.put("respondedAt", request.respondedAt().toString());
		}
		return body;
	}

	/**
	 * 交付延期（§6 /extend，单端点双角色）：推荐官（本人报名）携带 days/reason = 发起申请； 商家（任务 owner/门店经理）携带
	 * decision=approve|reject = 决定。批准与 deadline 后移同事务。
	 */
	@PostMapping("/api/tasks/{id}/applications/{appId}/extend")
	public Mono<ResponseEntity<Map<String, Object>>> extend(@PathVariable String id, @PathVariable String appId,
			@RequestBody(required = false) ApplicationExtendRequest body, ServerHttpRequest request) {
		return callers.resolve(request)
				.flatMap(caller -> apps.findById(appId).switchIfEmpty(fail(404, "报名不存在")).flatMap(app -> {
					if (!app.taskId().equals(id)) {
						return fail(404, "报名不存在");
					}
					boolean own = caller.accountId() != null && caller.accountId().equals(app.recommenderAccountId());
					if (own) {
						if (body == null || body.days() == null) {
							return fail(400, "申请延期需提供 days（正整数天数）");
						}
						return tasks.findById(id).switchIfEmpty(fail(404, "任务不存在"))
								.filter(task -> !task.isCommercePromotion())
								.switchIfEmpty(fail(409, "套餐推广按订单结算，无需申请延期"))
								.flatMap(task -> lifecycle.requestExtension(task, app, caller, body.days(),
										body.reason()))
								.map(ext -> ResponseEntity.ok(Map.of("success", true, "data", extensionBody(ext))));
					}
					// 决定侧：商家（组织 owner / 门店 MANAGER 实时重验）。
					return loadManageableTask(id, caller).filter(task -> !task.isCommercePromotion())
							.switchIfEmpty(fail(409, "套餐推广按订单结算，无延期流程")).flatMap(task -> {
								if (body == null || !body.hasValidDecision()) {
									return fail(400, "商家决定需提供 decision=approve|reject");
								}
								return lifecycle.decideExtension(task, app, caller, body.isApproval()).map(
										ext -> ResponseEntity.ok(Map.of("success", true, "data", extensionBody(ext))));
							});
				}));
	}

	private Mono<Task> loadManageableTask(String taskId, Caller caller) {
		return taskAuthorization.requireManager(taskId, caller).map(access -> access.task());
	}

	private static Map<String, Object> extensionBody(EngagementExtensionRepository.EngagementExtension ext) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", ext.id());
		m.put("applicationId", ext.applicationId());
		m.put("requestedBy", ext.requestedBy());
		m.put("days", ext.days());
		m.put("reason", ext.reason());
		m.put("status", ext.status());
		m.put("decidedBy", ext.decidedBy());
		m.put("decidedAt", ext.decidedAt() == null ? null : ext.decidedAt().toString());
		return m;
	}

	@GetMapping("/api/tasks/{id}/applications/{appId}/benefit")
	public Mono<ResponseEntity<Map<String, Object>>> benefitView(@PathVariable String id, @PathVariable String appId,
			ServerHttpRequest request) {
		return callers.resolve(request)
				.flatMap(caller -> apps.findById(appId).switchIfEmpty(fail(404, "报名不存在"))
						.filter(app -> app.taskId().equals(id)).switchIfEmpty(fail(404, "报名不存在"))
						.flatMap(app -> benefitPartyAuthorized(app, caller).flatMap(
								authorized -> authorized ? benefitService.benefitView(app) : fail(404, "报名不存在")))
						.map(data -> ResponseEntity.ok(Map.of("success", true, "data", data))));
	}

	/** 报名任一方（推荐官本人 / 商家管理侧）可见性判定。 */
	private Mono<Boolean> benefitPartyAuthorized(TaskApplication app, Caller caller) {
		boolean own = caller.accountId() != null && caller.accountId().equals(app.recommenderAccountId());
		if (own) {
			return Mono.just(true);
		}
		return tasks.findById(app.taskId()).flatMap(task -> taskAuthorization.canManage(task, caller))
				.defaultIfEmpty(false);
	}

	/**
	 * 体验权益动作（POST
	 * /benefit）：action=book/fulfill（推荐官本人）、confirm_fulfillment/respond_default
	 * （商家）、cancel（双方）。领域守卫见 ExperienceBenefitService。
	 */
	@PostMapping("/api/tasks/{id}/applications/{appId}/benefit")
	public Mono<ResponseEntity<Map<String, Object>>> benefitAction(@PathVariable String id, @PathVariable String appId,
			@RequestBody(required = false) BenefitActionRequest body, ServerHttpRequest request) {
		if (body == null || body.action() == null || body.action().isBlank()) {
			return fail(400, "action 必填");
		}
		String action = body.action().trim();
		return callers.resolve(request)
				.flatMap(caller -> apps.findById(appId).switchIfEmpty(fail(404, "报名不存在"))
						.filter(app -> app.taskId().equals(id)).switchIfEmpty(fail(404, "报名不存在"))
						.flatMap(app -> tasks.findById(id).switchIfEmpty(fail(404, "任务不存在")).flatMap(task -> {
							boolean own = caller.accountId() != null
									&& caller.accountId().equals(app.recommenderAccountId());
							Mono<Boolean> manager = own ? Mono.just(false) : taskAuthorization.canManage(task, caller);
							return manager.flatMap(isManager -> dispatchBenefitAction(task, app, caller, own, isManager,
									action, body));
						})).map(result -> ResponseEntity.ok(Map.of("success", true, "data", result))));
	}

	private Mono<Map<String, Object>> dispatchBenefitAction(Task task, TaskApplication app, Caller caller, boolean own,
			boolean isManager, String action, BenefitActionRequest body) {
		return switch (action) {
			case "book" -> {
				if (!own) {
					yield fail(403, "仅推荐官本人可预约权益");
				}
				yield benefitService.book(task, app, body.items(), body.bookingWindow())
						.map(benefitService::benefitBody);
			}
			case "fulfill" -> {
				if (!own) {
					yield fail(403, "仅推荐官本人可主张兑现");
				}
				yield benefitService.fulfill(task, app).map(benefitService::benefitBody);
			}
			case "confirm_fulfillment" -> {
				if (!isManager) {
					yield fail(403, "仅商家可确认兑现");
				}
				yield benefitService.confirmFulfillment(task, app, caller).map(benefitService::benefitBody);
			}
			case "cancel" -> {
				if (!own && !isManager) {
					yield fail(403, "无权操作该权益单");
				}
				yield benefitService.cancel(task, app).map(benefitService::benefitBody);
			}
			case "respond_default" -> {
				if (!isManager) {
					yield fail(403, "仅商家可回应失约主张");
				}
				yield benefitService.respondDefaultDenied(task, app).map(benefitService::benefitBody);
			}
			default -> fail(400, "未知 action：book/fulfill/confirm_fulfillment/cancel/respond_default");
		};
	}

	/**
	 * 商家失约主张（§6 POST /benefit/default-claim，推荐官举证发起）：商家限时回应窗见服务； 到期未回应由
	 * BenefitDefaultDispatcher 自动成立。
	 */
	@PostMapping("/api/tasks/{id}/applications/{appId}/benefit/default-claim")
	public Mono<ResponseEntity<Map<String, Object>>> benefitDefaultClaim(@PathVariable String id,
			@PathVariable String appId, ServerHttpRequest request) {
		return callers.requireRecommender(request)
				.flatMap(caller -> apps.findById(appId).switchIfEmpty(fail(404, "报名不存在"))
						.filter(app -> app.taskId().equals(id)).switchIfEmpty(fail(404, "报名不存在")).flatMap(app -> {
							if (!app.recommenderAccountId().equals(caller.accountId())) {
								return fail(403, "仅推荐官本人可发起失约主张");
							}
							return tasks.findById(id).switchIfEmpty(fail(404, "任务不存在"))
									.flatMap(task -> benefitService.claimDefault(task, app))
									.map(claimed -> ResponseEntity
											.ok(Map.of("success", true, "data", benefitService.benefitBody(claimed))));
						}));
	}

	/**
	 * 里程碑双方确认（§6 POST /milestones/{mid}/confirm）：报名任一方（推荐官本人 / 商家 owner）可调，
	 * 提出方不能自签（SQL 守卫）；已确认行幂等回读 200（TC96-010 事实不可变）。
	 */
	@PostMapping("/api/tasks/{id}/applications/{appId}/milestones/{milestoneId}/confirm")
	public Mono<ResponseEntity<Map<String, Object>>> confirmMilestone(@PathVariable String id,
			@PathVariable String appId, @PathVariable String milestoneId, ServerHttpRequest request) {
		return callers.resolve(request)
				.flatMap(caller -> apps.findById(appId).switchIfEmpty(fail(404, "报名不存在")).flatMap(app -> {
					if (!app.taskId().equals(id)) {
						return fail(404, "报名不存在");
					}
					boolean own = caller.accountId() != null && caller.accountId().equals(app.recommenderAccountId());
					// 注意 Mono<Void> 空信号：授权链用 then() 串联（requireManager 失败自带 403/404），
					// 不得对 Void 结果做 switchIfEmpty 补 404——空 Mono 恒触发，会把本人确认误判成 404。
					Mono<Void> authorized = own ? Mono.empty() : taskAuthorization.requireManager(id, caller).then();
					return authorized.then(tasks.findById(id).switchIfEmpty(fail(404, "任务不存在")))
							.flatMap(task -> milestoneService.confirm(task, app, caller, milestoneId))
							.map(confirmed -> ResponseEntity
									.ok(Map.of("success", true, "data", milestoneService.milestoneBody(confirmed))));
				}));
	}

	private static <T> Mono<T> fail(int status, String message) {
		return Mono.error(new MarketplaceException(status, message));
	}
}
