package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.analytics.AnalyticsModels.BusinessReport;
import com.grassland.marketplace.analytics.AnalyticsAdvice;
import com.grassland.marketplace.analytics.AnalyticsRepository;
import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.security.IdentityStoreAuthorizationClient;
import com.grassland.marketplace.reputation.ReputationService;
import com.grassland.identity.assertion.BackendRole;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * task-catalog HTTP 入口。草场 Epic 4 Slice 4A（HLD 5.3；4B 名额/限额；4F 赏金）+
 * GL-P1-TASK-001 Stage 1 生命周期。
 *
 * <ul>
 * <li>POST /api/tasks — 商家发布任务（<b>兼容路径，创建即 published</b>；断言 caller 须
 * merchant；owner=caller； organizationId 取请求体；outbox {@code TaskPublished}；同事务落
 * v1 {@code task_version} 快照）。</li>
 * <li>POST /api/tasks/draft — 创建草稿（merchant；draft tier 允许；不占发布额度）。</li>
 * <li>PUT /api/tasks/{id} — 编辑草稿（仅 draft 态；owner；expectedVersion 乐观锁）。</li>
 * <li>POST /api/tasks/{id}/publish — 发布草稿（owner；tier/额度/资金闸门；落快照；outbox
 * {@code TaskPublished}）。</li>
 * <li>POST /api/tasks/{id}/close —
 * 关闭报名（published→closed；owner；expectedVersion；已 closed 幂等 200，#26 D5）。</li>
 * <li>POST /api/tasks/{id}/cancel —
 * 取消任务（draft|published→cancelled；owner；expectedVersion）。</li>
 * <li>GET /api/tasks?organizationId=&status= — 列任务（默认 published；任意已登录 caller。非
 * published status 仅本 org merchant 可查）。</li>
 * <li>GET /api/tasks/{id} — 任务详情（published 对任意 caller 可见；其余状态仅 owner 可见，否则 404
 * 不泄露）。</li>
 * </ul>
 *
 * <p>
 * 身份靠 {@link MarketplaceCallerResolver} 消费 BFF 断言。资源级授权（merchant 确属该 org /
 * owner）服务端自查。 close/cancel/deadline 只门控「新报名」(apply)，不动既有
 * accept/confirm/结算（D-03 未决）。
 */
@RestController
public class TaskController {

	private final MarketplaceCallerResolver callers;
	private final TaskRepository tasks;
	private final TaskReviewRepository taskReviews;
	private final TaskReviewService taskReviewService;
	private final TaskPublishGate publishGate;
	private final OutboxRepository outbox;
	private final TaskApplicationRepository apps;
	private final FinanceEscrowClient finance;
	private final TransactionalOperator transactions;
	private final ReputationService reputationService;
	private final TaskResourceAuthorization taskAuthorization;
	private final TaskMetricsRepository metrics;
	private final AnalyticsRepository analytics;
	private final IdentityStoreAuthorizationClient identityStores;
	private final TaskStoreEnrichment storeEnrichment;
	private final TaskFullAutoCloser taskFullAutoCloser;

	public TaskController(MarketplaceCallerResolver callers, TaskRepository tasks, TaskReviewRepository taskReviews,
			OutboxRepository outbox, TaskReviewService taskReviewService, TaskPublishGate publishGate,
			TaskApplicationRepository apps, FinanceEscrowClient finance, TransactionalOperator transactions,
			ReputationService reputationService, TaskResourceAuthorization taskAuthorization,
			TaskMetricsRepository metrics, AnalyticsRepository analytics,
			IdentityStoreAuthorizationClient identityStores, TaskStoreEnrichment storeEnrichment,
			TaskFullAutoCloser taskFullAutoCloser) {
		this.callers = callers;
		this.tasks = tasks;
		this.taskReviews = taskReviews;
		this.taskReviewService = taskReviewService;
		this.publishGate = publishGate;
		this.outbox = outbox;
		this.apps = apps;
		this.finance = finance;
		this.transactions = transactions;
		this.reputationService = reputationService;
		this.taskAuthorization = taskAuthorization;
		this.metrics = metrics;
		this.analytics = analytics;
		this.identityStores = identityStores;
		this.storeEnrichment = storeEnrichment;
		this.taskFullAutoCloser = taskFullAutoCloser;
	}

	@PostMapping(value = "/api/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> create(@RequestBody CreateTaskRequest body,
			ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> taskAuthorization
				.requireScope(caller, body.organizationId(), blankToNull(body.storeId()), "manager")
				.flatMap(access -> transactions.transactional(tasks
						.acquireOrganizationPublishLock(access.organizationId())
						.then(enforcePublishGates(access.organizationId(), access.permissionTier(), body.bountyCents(),
								body.freebieDepositCents()))
						.then(enforceLadderBudget(body.requirements(), body.bountyCents()))
						.then(enforceQuestionPlatform(body.platform(), body.question()))
						.then(tasks.create(caller.accountId(), access.organizationId(), body.title(),
								body.description(), body.contentForm(), body.platform(), body.maxSlots(),
								body.bountyCents(), body.applicationDeadline(), body.minRecommenderLevel(),
								access.storeId(), body.requirements(), body.autoAcceptMinLevel(),
								body.freebieDepositCents(), body.question()))
						.flatMap(taskReviewService::submit))))
				.map(task -> ResponseEntity.status(201).body(Map.of("success", true, "data", toBody(task))));
	}

	/** 创建草稿。draft 创建不占发布额度、不需资金权限（草稿 tier 也可建）。 */
	@PostMapping(value = "/api/tasks/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> createDraft(@RequestBody CreateDraftRequest body,
			ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> taskAuthorization
				.requireScope(caller, body.organizationId(), blankToNull(body.storeId()), "manager")
				.flatMap(access -> transactions.transactional(enforceQuestionPlatform(body.platform(), body.question())
						.then(tasks.createDraft(caller.accountId(), access.organizationId(), body.title(),
								body.description(), body.contentForm(), body.platform(), body.maxSlots(),
								body.bountyCents(), body.applicationDeadline(), body.minRecommenderLevel(),
								access.storeId(), body.requirements(), body.autoAcceptMinLevel(),
								body.freebieDepositCents(), body.question())))))
				.map(task -> ResponseEntity.status(201).body(Map.of("success", true, "data", toBody(task))));
	}

	/** 编辑草稿（仅 draft 态；owner；expectedVersion 乐观锁）。 */
	@PutMapping(value = "/api/tasks/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> update(@PathVariable String id,
			@RequestBody UpdateTaskRequest body, ServerHttpRequest request) {
		return callers.requireUser(request)
				.flatMap(caller -> loadManageableTask(id, caller, "draft")
						.flatMap(current -> transactions.transactional(enforceFundingSingleMode(current,
								body.requirements(), body.bountyCents(), body.freebieDepositCents())
								.then(enforceInteractionBinding(body.contentForm(),
										body.requirements() == null ? current.requirements() : body.requirements()))
								.then(enforceLadderBudget(
										body.requirements() == null ? current.requirements() : body.requirements(),
										body.bountyCents()))
								.then(enforceQuestionPlatform(body.platform(), body.question()))
								.then(tasks.updateDraft(id, body.expectedVersion(), body.title(), body.description(),
										body.contentForm(), body.platform(), body.maxSlots(), body.bountyCents(),
										body.applicationDeadline(), body.minRecommenderLevel(), body.requirements(),
										body.autoAcceptMinLevel(), body.freebieDepositCents(), body.question())
										.switchIfEmpty(Mono.error(new MarketplaceException(409, "任务已变更，请刷新后重试")))
										.flatMap(task -> outbox.append(taskDraftUpdatedEnvelope(task))
												.thenReturn(task))))))
				.map(task -> ResponseEntity.ok(Map.of("success", true, "data", toBody(task))));
	}

	/**
	 * 提交草稿审核（GL-P2-ADMIN-003 全审：draft→pending_review；闸门仍跑；outbox
	 * TaskSubmittedForReview）。
	 */
	@PostMapping(value = "/api/tasks/{id}/publish", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> publish(@PathVariable String id,
			@RequestBody TaskLifecycleRequest body, ServerHttpRequest request) {
		return callers.requireUser(request)
				.flatMap(caller -> loadManageableTaskAccess(id, caller, "draft").flatMap(access -> transactions
						.transactional(tasks.acquireOrganizationPublishLock(access.task().organizationId())
								.then(enforcePublishGates(access.task().organizationId(), access.permissionTier(),
										access.task().bountyCents(), access.task().freebieDepositCents()))
								.then(enforceLadderBudget(access.task().requirements(), access.task().bountyCents()))
								.then(tasks.publish(id, body.expectedVersion())
										.switchIfEmpty(Mono.error(new MarketplaceException(409, "任务已变更，请刷新后重试")))
										.flatMap(taskReviewService::submit)))))
				.map(task -> ResponseEntity.ok(Map.of("success", true, "data", toBody(task))));
	}

	/**
	 * 关闭报名（published→closed；owner；expectedVersion）。
	 *
	 * <p>
	 * #26 D5 幂等化：任务已 closed（含满员自动关闭）→ <b>200 + 当前任务体</b>，不重复发事件、不校验 version
	 * （操作者重试语义）；仍为 published 但 version 不匹配 → 维持 409「任务已变更，请刷新后重试」；
	 * 其余状态（draft/pending_review/cancelled）维持
	 * 409。归属校验（{@code requireManager}）先于状态分支—— 非 owner 即便任务已 closed 也拿不到幂等 200。
	 */
	@PostMapping("/api/tasks/{id}/close")
	public Mono<ResponseEntity<Map<String, Object>>> close(@PathVariable String id,
			@RequestBody TaskLifecycleRequest body, ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> loadManageableTask(id, caller, null).flatMap(task -> {
			if (TaskStatus.CLOSED.dbValue().equals(task.status())) {
				return Mono.just(task); // 幂等重试：返回当前任务体，不重复发事件
			}
			if (!TaskStatus.PUBLISHED.dbValue().equals(task.status())) {
				return Mono.<Task>error(new MarketplaceException(409, "任务当前状态不允许该操作"));
			}
			return transactions.transactional(tasks.close(id, body.expectedVersion())
					.switchIfEmpty(Mono.error(new MarketplaceException(409, "任务已变更，请刷新后重试")))
					.flatMap(closed -> outbox.append(taskClosedEnvelope(closed)).thenReturn(closed)));
		})).map(task -> ResponseEntity.ok(Map.of("success", true, "data", toBody(task))));
	}

	/**
	 * 取消任务（draft|published→cancelled；owner；expectedVersion）。
	 *
	 * <p>
	 * D-03 §5：cancel 视商家违约——已 accept 但<b>未提交凭证</b>的 engagement 全额返还商家（首期无补偿），
	 * 并记违约信号（trust 声誉未建，事件先落库）。已提交/核实通过的履约<b>不动</b>，照常结算（其确认窗口继续）。 release 不在
	 * task-cancel 事务内（finance HTTP）；release 幂等 + 事件确定性 ⇒ 崩溃安全。退款失败向上抛 5xx； task 已
	 * cancelled 时重复调用会跳过状态迁移并重跑退款，收敛「cancel 已提交、release 尚未完成」间隙。
	 */
	@PostMapping("/api/tasks/{id}/cancel")
	public Mono<ResponseEntity<Map<String, Object>>> cancel(@PathVariable String id,
			@RequestBody TaskLifecycleRequest body, ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> loadManageableTask(id, caller, null).flatMap(owned -> {
			String status = owned.status();
			if (TaskStatus.CANCELLED.dbValue().equals(status)) {
				return refundAcceptedWithoutSubmission(owned)
						.map(count -> ResponseEntity.ok(Map.of("success", true, "data", cancelBody(owned, count))));
			}
			if (!TaskStatus.DRAFT.dbValue().equals(status) && !TaskStatus.PUBLISHED.dbValue().equals(status)
					&& !TaskStatus.PENDING_REVIEW.dbValue().equals(status)) {
				return Mono.<ResponseEntity<Map<String, Object>>>error(new MarketplaceException(409, "任务已结束，不可取消"));
			}
			return transactions
					.transactional(tasks.cancel(id, body.expectedVersion())
							.switchIfEmpty(Mono.error(new MarketplaceException(409, "任务已变更，请刷新后重试")))
							.flatMap(task -> outbox.append(taskCancelledEnvelope(task)).thenReturn(task)))
					.flatMap(task -> refundAcceptedWithoutSubmission(task).map(refundedCount -> ResponseEntity
							.ok(Map.of("success", true, "data", cancelBody(task, refundedCount)))));
		}));
	}

	/**
	 * 退还本任务「已 accept 未提交凭证」的 engagement（D-03 §5），按资金来源分支（ADR-D12 D6 关键差异行）： bounty
	 * 履约 → finance release（全额返<b>商家</b>）；freebie 履约 → finance freebie
	 * refund（押金退<b>推荐官</b>—— 商家取消不是推荐官的失败）。两条路径都置终态 {@code refunded} + outbox
	 * {@code EngagementRefundedOnCancel} （违约信号供 trust 消费 +
	 * 双方通知，payload.refundDirection 标记资金去向）。失败向上抛；cancel 重试会再次执行（两侧幂等）。
	 *
	 * <p>
	 * 退款幂等（404/409 视作成功）后必须把 application 置终态 {@code refunded}：留在 accepted 会让推荐官
	 * 侧一直显示「进行中且可提交」（提交已被 cancelled 校验拒），且每次 cancel 重试都重复退款 + 重复通知。 状态流转与 outbox
	 * append 同事务，保证「已退款 ⇔ 已通知」。
	 */
	private Mono<Integer> refundAcceptedWithoutSubmission(Task task) {
		return apps.findAcceptedByTaskWithoutSubmission(task.id())
				.concatMap(app -> refundOnCancel(task, app)
						.then(transactions.transactional(apps.markRefunded(app.id(), task.id()).flatMap(
								refunded -> outbox.append(engagementRefundedEnvelope(task, refunded)).thenReturn(1))))
						.defaultIfEmpty(0))
				.reduce(0, Integer::sum);
	}

	/** 任务书 #46 组合模式：两腿各自退还——押金退推荐官（商家取消不是推荐官的失败），赏金 release 返商家。 */
	private Mono<Void> refundOnCancel(Task task, TaskApplication app) {
		Mono<Void> freebieLeg = app.freebieDepositCents() > 0
				? finance.freebieRefund(task.organizationId(), app.id())
				: Mono.empty();
		Mono<Void> bountyLeg = app.bountyCents() > 0 ? finance.release(task.organizationId(), app.id()) : Mono.empty();
		return freebieLeg.then(bountyLeg);
	}

	/**
	 * 修订已发布任务（GL-P1-TASK-001：编辑出新版本）。
	 *
	 * <p>
	 * owner + published + <b>无人报名成功</b>（PRD §2.3：accepted / reserving 任一存在即 409 「已有
	 * N 名推荐官报名成功，任务不可再修改」，pending 报名不阻塞）；乐观锁；赏金变更走 tier 闸门
	 * （{@link #enforceBountyTierGate}，与发布同口径但不占额度）。推荐官被接受那一刻冻结 {@code bounty_cents}
	 * 快照（V14 snapshot-pinning），按快照结算。每次修订 version+1 + 新 task_version 快照 + outbox
	 * TaskRevised。
	 *
	 * <p>
	 * #26 D13：修订提交成功的同一事务末尾仍触发一次 {@link TaskFullAutoCloser#closeIfFull}—— 报名守卫（PRD
	 * §2.3）上线后常规路径已无「带 accepted 修订」，此处保留为并发竞态兜底： 守卫计数与落库之间理论上可有 pending 报名被接受，满员即收口
	 * closed（同事务发 {@code TaskClosed}/slots_full）。
	 */
	@PostMapping(value = "/api/tasks/{id}/revise", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> revise(@PathVariable String id,
			@RequestBody ReviseTaskRequest body, ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> loadManageableTaskAccess(id, caller, "published")
				.flatMap(access -> guardReviseApplications(id)
						.then(enforceBountyTierGate(access.permissionTier(), body.bountyCents(),
								body.freebieDepositCents()))
						.then(enforceFundingSingleMode(access.task(), body.requirements(), body.bountyCents(),
								body.freebieDepositCents()))
						.then(enforceInteractionBinding(body.contentForm(),
								body.requirements() == null ? access.task().requirements() : body.requirements()))
						.then(enforceLadderBudget(
								body.requirements() == null ? access.task().requirements() : body.requirements(),
								body.bountyCents()))
						.then(enforceQuestionPlatform(body.platform(), body.question())).thenReturn(access.task())
						.flatMap(v -> transactions.transactional(tasks
								.revisePublished(id, body.expectedVersion(), body.title(), body.description(),
										body.contentForm(), body.platform(), body.maxSlots(), body.bountyCents(),
										body.applicationDeadline(), body.minRecommenderLevel(), body.requirements(),
										caller.accountId(), body.autoAcceptMinLevel(), body.freebieDepositCents(),
										body.question())
								.switchIfEmpty(Mono.error(new MarketplaceException(409, "任务已变更，请刷新后重试")))
								.flatMap(task -> outbox.append(taskRevisedEnvelope(task)).thenReturn(task))
								// #26 D13：修订提交成功的同事务末尾判定满员收口——下调 maxSlots
								// 至已接受数之下时任务即转 closed（同事务发 TaskClosed/slots_full）；
								// 未满/无上限 → empty，回落修订后的任务体（响应返回最终状态与版本）
								.flatMap(revised -> taskFullAutoCloser.closeIfFull(revised.id())
										.defaultIfEmpty(revised))))))
				.map(task -> ResponseEntity.ok(Map.of("success", true, "data", toBody(task))));
	}

	/**
	 * PRD §2.3 修订守卫：有人报名成功（accepted + reserving，reserving 为资金预留中的在途态） 即冻结修改入口。仓储层
	 * {@code revisePublished} 的 UPDATE 另内联 NOT EXISTS 兜底计数与落库间的竞态。
	 */
	private Mono<Void> guardReviseApplications(String taskId) {
		return apps.countAcceptedOrReservingByTask(taskId)
				.flatMap(count -> count > 0
						? Mono.error(new MarketplaceException(409, "已有 " + count + " 名推荐官报名成功，任务不可再修改"))
						: Mono.empty());
	}

	/**
	 * 修订资金型字段的 tier 闸门：赏金或押金任一 &gt;0 都须有交易权限且 ≤ 本组织单笔上限（ADR-D12 D5： 押金涉及托管与商家收款，与
	 * bounty 同一 funding 闸门）。与发布同口径，但<b>不算 active/monthly 额度</b>——
	 * 修订不是新发布，任务已在额度内。防止商家借修订把资金字段抬到 tier 之上。
	 */
	/**
	 * 任务书 #62 P4：目标问题只对 platform=zhihu 有意义（回答挂在知乎问题下）。 非知乎携带 → 422（well-formed
	 * 但语义不可处理），不静默丢弃——丢弃会让商家以为 派了回答形态任务，而创作端根本不会锁定回答模式。
	 */
	private Mono<Void> enforceQuestionPlatform(String platform, TaskQuestion question) {
		if (!TaskQuestion.orNone(question).present()) {
			return Mono.empty();
		}
		if (!"zhihu".equalsIgnoreCase(platform == null ? "" : platform.trim())) {
			return Mono.error(new MarketplaceException(422, "目标问题仅支持知乎平台任务"));
		}
		return Mono.empty();
	}

	private Mono<Void> enforceBountyTierGate(String permissionTier, Long bountyCents, Long freebieDepositCents) {
		MerchantTier tier = MerchantTier.fromDb(permissionTier);
		long bounty = bountyCents == null ? 0L : bountyCents;
		long deposit = freebieDepositCents == null ? 0L : freebieDepositCents;
		long maxTx = PublishQuotaPolicy.maxTxAmountCents(tier);
		if ((bounty > 0 || deposit > 0) && maxTx == 0) {
			return Mono.error(new MarketplaceException(403, "当前等级不可发布资金型任务"));
		}
		if (bounty > maxTx) {
			return Mono.error(new MarketplaceException(409, "赏金超出本组织单笔上限"));
		}
		if (deposit > maxTx) {
			return Mono.error(new MarketplaceException(409, "押金超出本组织单笔上限"));
		}
		return Mono.empty();
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

	/**
	 * 任务书 #23 R2 交叉校验（update/revise 用合并视图）：requirements=null 表示「保留现值」，
	 * contentForm=null 表示「清空内容形式」，须与现行 requirements 合并后再判
	 * {@code contentForm=interaction ⇔ requirements.interaction 非空}。
	 */
	/**
	 * 付费方式三选一（PRD §2.2 2026-08-22 决策）的合并视图校验：update/revise 的资金字段 null=保留现值，请求体构造器里的
	 * {@link TaskCatalogFundingRules} 看不到现值， 部分更新可夹带出组合（如现有赏金任务只 PUT 押金）。这里按「现值 ∪
	 * 请求值」终判。
	 */
	private Mono<Void> enforceFundingSingleMode(Task current, TaskRequirements requirements, Long bountyCents,
			Long freebieDepositCents) {
		try {
			TaskCatalogFundingRules.validate(requirements == null ? current.requirements() : requirements,
					freebieDepositCents == null ? current.freebieDepositCents() : freebieDepositCents,
					bountyCents == null ? current.bountyCents() : bountyCents);
		} catch (IllegalArgumentException error) {
			return Mono.error(new MarketplaceException(400, error.getMessage()));
		}
		return Mono.empty();
	}

	private Mono<Void> enforceInteractionBinding(String contentForm, TaskRequirements mergedRequirements) {
		try {
			TaskRequirements.validateInteractionBinding(contentForm, mergedRequirements);
		} catch (IllegalArgumentException error) {
			return Mono.error(new MarketplaceException(400, error.getMessage()));
		}
		return Mono.empty();
	}

	@GetMapping("/api/tasks")
	public Mono<ResponseEntity<Map<String, Object>>> list(@RequestParam String organizationId,
			@RequestParam(required = false, defaultValue = "published") String status,
			@RequestParam(required = false) String storeId, @RequestParam(required = false) String q,
			ServerHttpRequest request) {
		String query = searchQuery(q);
		return callers.resolve(request).flatMap(caller -> {
			if (storeId != null && !storeId.isBlank()) {
				return taskAuthorization.requireScope(caller, organizationId, storeId, "staff")
						.then(tasks.findByStore(organizationId, storeId, status, query).collectList())
						.flatMap(this::enrichTasks)
						.map(list -> ResponseEntity.ok(Map.of("success", true, "data", list)));
			}
			// 非 published status 仅本 org merchant 可查（防跨组织草稿/取消泄露）。
			String effectiveStatus = TaskStatus.PUBLISHED.dbValue().equalsIgnoreCase(status) || status.isBlank()
					? TaskStatus.PUBLISHED.dbValue()
					: (caller.isMerchant() && organizationId.equals(caller.organizationId())
							? status
							: TaskStatus.PUBLISHED.dbValue());
			boolean ownerView = caller.isMerchant() && organizationId.equals(caller.organizationId());
			// owner 全量视角：组织级 + 全部门店任务（门店任务的管理入口在主体工作台，
			// 不传 storeId 时不得隐式排除）；推荐官浏览仍只见组织级 published。
			Mono<List<Task>> visibleTasks = ownerView
					? tasks.findAllScopesByOrganization(organizationId, effectiveStatus, query).collectList()
					: visibleRecommenderLevel(caller)
							.flatMap(level -> tasks.findByOrganization(organizationId, effectiveStatus, query)
									.filter(task -> !TaskStatus.PUBLISHED.dbValue().equals(task.status())
											|| task.minRecommenderLevel() <= level)
									.collectList());
			return visibleTasks.flatMap(this::enrichTasks)
					.map(list -> ResponseEntity.ok(Map.of("success", true, "data", list)));
		});
	}

	/**
	 * Merchant analytics read model. Marketing fields stay truthful when only
	 * Sandbox events are present.
	 */
	@GetMapping("/api/tasks/analytics")
	public Mono<ResponseEntity<Map<String, Object>>> analytics(@RequestParam String organizationId,
			@RequestParam(required = false) String storeId, @RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to, ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> taskAuthorization
				.requireScope(caller, organizationId, blankToNull(storeId), "staff")
				.flatMap(access -> Mono.zip(metrics.dashboard(access.organizationId(), access.storeId(), from, to),
						analytics.report(access.organizationId(), access.storeId(), from, to))))
				.map(tuple -> ResponseEntity
						.ok(Map.of("success", true, "data", dashboardBody(tuple.getT1(), tuple.getT2()))));
	}

	// ---------- 任务内容审核（GL-P2-ADMIN-003 全审政策）已迁移至 TaskReviewAdminController ----------

	/**
	 * 全局任务大厅（GL-P1-TASK-001 Stage 2）：跨组织 feed，仅 published 且未截止。
	 *
	 * <p>
	 * 任意已登录 caller 可查（推荐官浏览大厅）。keyset 游标分页（{@code created_at DESC, id DESC}）， 筛选
	 * platform/contentForm/minBountyCents；距离筛选通过 Identity 的权威门店坐标先解析门店集合， 再进入本服务
	 * keyset 查询，避免客户端逐页过滤造成漏项。 响应 {@code {items, nextCursor, hasMore}}，与按 org 的
	 * {@code GET /api/tasks} 裸数组形状区分。 路由字面量 {@code feed} 在 PathPattern 优先于
	 * {@code {id}}，命中既有 {@code /api/tasks**} BFF flag。
	 */
	@GetMapping("/api/tasks/feed")
	public Mono<ResponseEntity<Map<String, Object>>> feed(@RequestParam(required = false) String platform,
			@RequestParam(required = false) String contentForm, @RequestParam(required = false) Long minBountyCents,
			@RequestParam(required = false) Double latitude, @RequestParam(required = false) Double longitude,
			@RequestParam(required = false) Double maxDistanceKm, @RequestParam(required = false) String q,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false, defaultValue = "20") int limit, ServerHttpRequest request) {
		String query = searchQuery(q);
		return callers.resolve(request).flatMap(caller -> {
			int safeLimit = Math.max(1, Math.min(limit, 50));
			FeedCursor decoded = FeedCursor.decode(cursor);
			boolean anyDistance = latitude != null || longitude != null || maxDistanceKm != null;
			if (anyDistance && !validDistanceQuery(latitude, longitude, maxDistanceKm)) {
				return Mono.error(new MarketplaceException(400, "距离筛选需提供有效经纬度，范围须在 0.1 至 200 公里之间"));
			}
			Mono<List<IdentityStoreAuthorizationClient.NearbyStore>> nearby = anyDistance
					? identityStores.nearby(latitude, longitude, maxDistanceKm)
					: Mono.just(List.of());
			return Mono.zip(visibleRecommenderLevel(caller), nearby).flatMap(tuple -> {
				List<IdentityStoreAuthorizationClient.NearbyStore> nearbyStores = tuple.getT2();
				if (anyDistance && nearbyStores.isEmpty()) {
					return Mono.just(feedBody(List.of(), safeLimit, Map.of(), Map.of()));
				}
				List<String> storeIds = anyDistance
						? nearbyStores.stream().map(IdentityStoreAuthorizationClient.NearbyStore::storeId).toList()
						: null;
				Map<String, Double> distances = nearbyStores.stream()
						.collect(Collectors.toMap(IdentityStoreAuthorizationClient.NearbyStore::storeId,
								IdentityStoreAuthorizationClient.NearbyStore::distanceKm, Math::min));
				TaskRepository.FeedFilter filter = new TaskRepository.FeedFilter(blankToNull(platform),
						blankToNull(contentForm),
						(minBountyCents == null || minBountyCents < 0) ? null : minBountyCents, tuple.getT1(), storeIds,
						query);
				return tasks.findFeed(filter, decoded == null ? null : decoded.ts(),
						decoded == null ? null : decoded.id(), safeLimit + 1).collectList()
						.flatMap(rows -> enrichFeed(rows, safeLimit, distances));
			});
		});
	}

	/**
	 * 任务书 #24：feed 门店块增强。keyset 分页每页最多 limit+1 行，只对页内去重后的 storeId 一次批量拉
	 * identity（不逐行）；distanceKm 逻辑不动。
	 */
	private Mono<ResponseEntity<Map<String, Object>>> enrichFeed(List<Task> rows, int limit,
			Map<String, Double> distances) {
		boolean hasMore = rows.size() > limit;
		List<Task> page = hasMore ? rows.subList(0, limit) : rows;
		List<String> pageStoreIds = page.stream().map(Task::storeId).filter(java.util.Objects::nonNull).distinct()
				.toList();
		return storeEnrichment.loadStoreBlocks(pageStoreIds).map(stores -> feedBody(rows, limit, distances, stores));
	}

	/** 组装 feed 分页体：取 limit+1 判 hasMore，nextCursor 为本页最后一行的 (created_at, id)。 */
	private ResponseEntity<Map<String, Object>> feedBody(List<Task> rows, int limit, Map<String, Double> distances,
			Map<String, Map<String, Object>> stores) {
		boolean hasMore = rows.size() > limit;
		List<Task> page = hasMore ? rows.subList(0, limit) : rows;
		String nextCursor = hasMore && !page.isEmpty() ? FeedCursor.encode(page.get(page.size() - 1)) : null;
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("items", page.stream().map(task -> {
			Map<String, Object> body = toBody(task);
			if (task.storeId() != null && distances.containsKey(task.storeId())) {
				body.put("distanceKm", Math.round(distances.get(task.storeId()) * 10d) / 10d);
			}
			if (task.storeId() != null && stores.containsKey(task.storeId())) {
				body.put("store", stores.get(task.storeId()));
			}
			return body;
		}).toList());
		data.put("nextCursor", nextCursor);
		data.put("hasMore", hasMore);
		return ResponseEntity.ok(Map.of("success", true, "data", data));
	}

	private static boolean validDistanceQuery(Double latitude, Double longitude, Double radiusKm) {
		return latitude != null && longitude != null && radiusKm != null && Double.isFinite(latitude) && latitude >= -90
				&& latitude <= 90 && Double.isFinite(longitude) && longitude >= -180 && longitude <= 180
				&& Double.isFinite(radiusKm) && radiusKm >= 0.1 && radiusKm <= 200;
	}

	/**
	 * 本组织的发布用量（D-05 额度的「已用」侧）。
	 *
	 * <p>
	 * 补 identity {@code GET /api/organizations/{orgId}/quota} 的缺口——那里只给**上限** （策略归
	 * identity 的 {@code PermissionQuotaPolicy} 所有），用量在 marketplace 这侧。
	 * 前端把两者合并展示为「已用 N / 上限 M」。
	 *
	 * <p>
	 * 刻意<b>只回用量、不回上限</b>：上限已在 identity 与本服务的 {@link PublishQuotaPolicy}
	 * 两处镜像（靠单测锁值防漂移），再加第三处只会多一个漂移点。
	 *
	 * <p>
	 * 路由放在 {@code /api/tasks/*} 下，命中 edge-bff 既有的 {@code /api/tasks**} 前缀，无需新增 BFF
	 * 路由。 字面量段 {@code usage} 在 PathPattern 里优先级高于 {@code {id}} 模板，不会被详情端点抢走。
	 */
	@GetMapping("/api/tasks/usage")
	public Mono<ResponseEntity<Map<String, Object>>> usage(@RequestParam String organizationId,
			ServerHttpRequest request) {
		return callers.requireMerchant(request).flatMap(merchant -> {
			// org 归属自查，与发布闸门 1 同口径：不能查别家组织的用量。
			if (!organizationId.equals(merchant.organizationId())) {
				return Mono.<ResponseEntity<Map<String, Object>>>error(new MarketplaceException(403, "无权查询该组织用量"));
			}
			MerchantTier tier = MerchantTier.fromDb(merchant.permissionTier());
			int maxActive = PublishQuotaPolicy.maxActiveTasks(tier);
			int maxMonthly = PublishQuotaPolicy.maxMonthlyTasks(tier);
			long maxTx = PublishQuotaPolicy.maxTxAmountCents(tier);
			return tasks.countActiveByOrganization(organizationId).flatMap(active -> tasks
					.countCreatedThisMonthByOrganization(organizationId)
					.map(monthly -> ResponseEntity.ok(Map.of("success", true, "data", Map.of("organizationId",
							organizationId, "activeTasks", active, "monthlyTasks", monthly, "maxActiveTasks", maxActive,
							"remainingActiveTasks", Math.max(0, maxActive - active), "maxMonthlyTasks", maxMonthly,
							"remainingMonthlyTasks", Math.max(0, maxMonthly - monthly), "maxTxAmountCents", maxTx)))));
		});
	}

	@GetMapping("/api/tasks/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> get(@PathVariable String id, ServerHttpRequest request) {
		return callers.resolve(request).flatMap(caller -> tasks.findById(id)
				.switchIfEmpty(Mono.error(new MarketplaceException(404, "任务不存在"))).flatMap(task -> {
					// published 对任意 caller 可见；其余状态仅 owner 可见（不泄露 draft/closed/cancelled 存在）。
					boolean publicVisible = TaskStatus.PUBLISHED.dbValue().equals(task.status());
					boolean owner = caller.accountId().equals(task.ownerAccountId());
					if (!publicVisible && task.storeId() != null) {
						return taskAuthorization.requireScope(caller, task.organizationId(), task.storeId(), "staff")
								.then(okWithStore(task, true));
					}
					if (owner && task.storeId() == null) {
						return okWithStore(task, true);
					}
					if (!publicVisible) {
						return Mono.error(new MarketplaceException(404, "任务不存在"));
					}
					return visibleRecommenderLevel(caller).filter(level -> level >= task.minRecommenderLevel())
							.flatMap(level -> okWithStore(task, false))
							.switchIfEmpty(Mono.error(new MarketplaceException(404, "任务不存在")));
				}));
	}

	/** 任务书 #24：任务详情携带门店公开块（storeName/city/categories）；无门店/降级时不带 store 键。 */
	private Mono<ResponseEntity<Map<String, Object>>> okWithStore(Task task) {
		return okWithStore(task, false);
	}

	/**
	 * 详情响应组装。withProgress：管理视角（owner / 门店 staff）附带 progress 块——含
	 * acceptedApplicationCount（PRD §2.3，前端据此禁用修订入口）；公开视角刻意不带， progress 含
	 * settledBountyCents 等商家经营数据，不向推荐官泄露。
	 */
	private Mono<ResponseEntity<Map<String, Object>>> okWithStore(Task task, boolean withProgress) {
		Mono<Map<String, Object>> body = withProgress
				? metrics.findProgressByTaskIds(List.of(task.id())).next().map(facts -> {
					Map<String, Object> enriched = toBody(task);
					enriched.put("progress", progressBody(task, facts));
					return enriched;
				}).defaultIfEmpty(toBody(task))
				: Mono.just(toBody(task));
		if (task.storeId() == null) {
			return body.map(b -> ResponseEntity.ok(Map.of("success", true, "data", b)));
		}
		return body.zipWith(storeEnrichment.loadStoreBlocks(List.of(task.storeId()))).map(tuple -> {
			Map<String, Object> enriched = tuple.getT1();
			Map<String, Object> block = tuple.getT2().get(task.storeId());
			if (block != null) {
				enriched.put("store", block);
			}
			return ResponseEntity.ok(Map.of("success", true, "data", enriched));
		});
	}

	private Mono<Integer> visibleRecommenderLevel(Caller caller) {
		return reputationService.snapshot(caller.accountId())
				.map(snapshot -> snapshot.evaluation().effectiveLevel().number());
	}

	/**
	 * 发布闸门 2-5（tier / 资金权限 / 单笔上限 / 活跃额度 / 月度额度）。immediate-create
	 * 内联同款；draft→publish 复用。 闸门 1（org 归属）由 {@link #loadOwnedTask} 隐含（owner 必属该
	 * org）。
	 */
	private Mono<Void> enforcePublishGates(String organizationId, String permissionTier, Long bountyCents,
			Long freebieDepositCents) {
		return publishGate.enforce(organizationId, permissionTier, bountyCents, freebieDepositCents);
	}

	/** 组织级任务沿用 owner 管理；门店任务允许该店 MANAGER 管理。 */
	private Mono<Task> loadManageableTask(String taskId, Caller caller, String requiredStatus) {
		return loadManageableTaskAccess(taskId, caller, requiredStatus)
				.map(TaskResourceAuthorization.ManagedTask::task);
	}

	private Mono<TaskResourceAuthorization.ManagedTask> loadManageableTaskAccess(String taskId, Caller caller,
			String requiredStatus) {
		return taskAuthorization.requireManager(taskId, caller)
				.filter(access -> requiredStatus == null || requiredStatus.equals(access.task().status()))
				.switchIfEmpty(fail(409, "任务当前状态不允许该操作"));
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

	private EventEnvelope taskDraftUpdatedEnvelope(Task task) {
		return new EventEnvelope(UUID.randomUUID().toString(), "TaskDraftUpdated", "Task", task.id(), task.version(),
				Instant.now(), null, taskEventPayload(task, false));
	}

	/**
	 * #26：payload/envelope 构造抽到 {@link TaskFullAutoCloser}，与满员自动关闭路径共用（两条路径 payload
	 * 键完全一致，D13）。
	 */
	private EventEnvelope taskClosedEnvelope(Task task) {
		return TaskFullAutoCloser.taskClosedEnvelope(task, "manual");
	}

	private EventEnvelope taskCancelledEnvelope(Task task) {
		return new EventEnvelope(UUID.randomUUID().toString(), "TaskCancelled", "Task", task.id(), task.version(),
				Instant.now(), null, taskEventPayload(task, false));
	}

	/**
	 * D-03 §5 cancel 退款事件：商家取消任务，已 accept 未提交凭证的 engagement 按资金来源退款（ADR-D12 D6）：
	 * {@code refundDirection=merchant}（bounty，返商家）/{@code recommender}（freebie
	 * 押金，退推荐官）。 确定性 eventId（type-3 {@code EngagementRefundedOnCancel:<appId>}）保证重跑
	 * exactly-once； reason={@code merchant_cancel} 供 trust
	 * 声誉消费（违约计数，D-05）。双方收件（identity 通知中心）。
	 */
	private EventEnvelope engagementRefundedEnvelope(Task task, TaskApplication app) {
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

	/** cancel 响应：附 refundedCount（已退还的未提交履约数）。 */
	private Map<String, Object> cancelBody(Task task, int refundedCount) {
		Map<String, Object> m = toBody(task);
		m.put("refundedCount", refundedCount);
		return m;
	}

	private EventEnvelope taskRevisedEnvelope(Task task) {
		return new EventEnvelope(UUID.randomUUID().toString(), "TaskRevised", "Task", task.id(), task.version(),
				Instant.now(), null, taskEventPayload(task, false));
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

	private Map<String, Object> toBody(Task task) {
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

	private static <T> Mono<T> fail(int status, String message) {
		return Mono.error(new MarketplaceException(status, message));
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

	private Mono<List<Map<String, Object>>> enrichTasks(List<Task> rows) {
		return metrics.findProgressByTaskIds(rows.stream().map(Task::id).toList()).collectMap(TaskProgress::taskId)
				.map(progress -> rows.stream().map(task -> {
					Map<String, Object> body = toBody(task);
					TaskProgress facts = progress.getOrDefault(task.id(), TaskProgress.empty(task.id()));
					body.put("progress", progressBody(task, facts));
					return body;
				}).toList());
	}

	private static Map<String, Object> progressBody(Task task, TaskProgress facts) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("totalApplications", facts.totalApplications());
		body.put("pendingApplications", facts.pendingApplications());
		body.put("reservingApplications", facts.reservingApplications());
		body.put("acceptedApplications", facts.acceptedApplications());
		// PRD §2.3：已报名成功人数（accepted + reserving）——前端据此禁用「编辑」并给行内原因。
		body.put("acceptedApplicationCount", facts.acceptedApplications() + facts.reservingApplications());
		body.put("rejectedApplications", facts.rejectedApplications());
		body.put("withdrawnApplications", facts.withdrawnApplications());
		body.put("refundedApplications", facts.refundedApplications());
		body.put("occupiedSlots", facts.occupiedSlots());
		body.put("maxSlots", task.maxSlots());
		body.put("remainingSlots",
				task.maxSlots() == null ? null : Math.max(0, task.maxSlots() - facts.occupiedSlots()));
		body.put("submittedDeliverables", facts.submittedDeliverables());
		body.put("confirmedDeliverables", facts.confirmedDeliverables());
		body.put("settledEngagements", facts.settledEngagements());
		body.put("reservedBountyCents", facts.reservedBountyCents());
		body.put("settledBountyCents", facts.settledBountyCents());
		return body;
	}

	private static Map<String, Object> dashboardBody(MerchantDashboard dashboard, BusinessReport report) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("organizationId", dashboard.organizationId());
		body.put("storeId", dashboard.storeId());
		body.put("taskCount", dashboard.taskCount());
		body.put("publishedTaskCount", dashboard.publishedTaskCount());
		body.put("totalApplications", dashboard.totalApplications());
		body.put("acceptedApplications", dashboard.acceptedApplications());
		body.put("confirmedDeliverables", dashboard.confirmedDeliverables());
		body.put("settledEngagements", dashboard.settledEngagements());
		body.put("reservedBountyCents", dashboard.reservedBountyCents());
		body.put("settledBountyCents", dashboard.settledBountyCents());
		body.put("applicationAcceptanceRate", dashboard.applicationAcceptanceRate());
		body.put("averageRating", dashboard.averageRating());
		var attribution = report.attribution();
		Map<String, Object> marketing = new LinkedHashMap<>();
		marketing.put("exposureCollected", attribution.exposures() > 0);
		marketing.put("interactionCollected", attribution.interactions() > 0);
		marketing.put("conversionCollected", attribution.conversions() > 0);
		marketing.put("exposures", attribution.exposures());
		marketing.put("interactions", attribution.interactions());
		marketing.put("conversions", attribution.conversions());
		marketing.put("attributedRevenueCents", attribution.attributedRevenueCents());
		marketing.put("attributedRefundCents", attribution.attributedRefundCents());
		marketing.put("dataQuality", attribution.dataQuality());
		marketing.put("status", attribution.status());
		marketing.put("roi", attribution.roi() == null ? "unavailable" : attribution.roi());
		marketing.put("roiFormula", "(attributedRevenue-attributedRefund-settledBounty)/settledBounty");
		body.put("marketingMetrics", marketing);
		var guidance = AnalyticsAdvice.evaluate(report);
		body.put("advice", guidance.advice());
		body.put("alerts", guidance.alerts());
		body.put("businessMetrics",
				Map.of("orders", report.orders(), "paidOrders", report.paidOrders(), "redeemedOrders",
						report.redeemedOrders(), "refundedOrders", report.refundedOrders(), "grossGmvCents",
						report.grossGmvCents(), "refundedGmvCents", report.refundedGmvCents(), "netGmvCents",
						report.netGmvCents(), "merchantRevenueCents", report.merchantRevenueCents(), "platformFeeCents",
						report.platformFeeCents(), "recommenderRevenueCents", report.recommenderRevenueCents()));
		return body;
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

	/**
	 * feed keyset 游标（GL-P1-TASK-001 Stage 2）：opaque base64url 编码
	 * {@code createdAt|id}。 坏游标 → decode 返回 null（当首页，不报错），避免前端持有过期游标时硬失败。
	 */
	record FeedCursor(Instant ts, String id) {
		static String encode(Task task) {
			String raw = task.createdAt().toString() + "|" + task.id();
			return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
		}

		static FeedCursor decode(String cursor) {
			if (cursor == null || cursor.isBlank()) {
				return null;
			}
			try {
				String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
				int sep = raw.lastIndexOf('|');
				if (sep <= 0 || sep == raw.length() - 1) {
					return null;
				}
				return new FeedCursor(Instant.parse(raw.substring(0, sep)), raw.substring(sep + 1));
			} catch (Exception error) {
				return null;
			}
		}
	}
}
