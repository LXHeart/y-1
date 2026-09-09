package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.benefit.ExperienceBenefit;
import com.grassland.marketplace.benefit.ExperienceBenefitRepository;
import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.matching.TaskRecommenderInvitationRepository;
import com.grassland.marketplace.milestone.EngagementMilestoneService;
import com.grassland.marketplace.reputation.ReputationService;
import com.grassland.marketplace.reputation.ReputationSnapshot;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import com.grassland.marketplace.workflow.FinanceEscrowException;
import com.grassland.marketplace.workflow.saga.DisputeChecker;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 报名生命周期领域服务：推荐官报名/撤销、商家（批量）拒绝、任务报名列表（owner 按声誉权重排序 / 非 owner
 * 仅本人）与任务进度统计。任务加载与资源级自查由控制器守卫完成后传入。
 *
 * <p>
 * 任务书 #96 C96-01：新增推荐官无责退出（accepted+无提交+无确认里程碑，§5.1）与 交付延期申请/商家批准（§6
 * /extend）。资金腿照 D-03 §5 惯例：finance HTTP 在本地事务外先落， guarded 状态迁移 + outbox
 * 随后同事务（两侧幂等，重试收敛）。
 */
@Component
public class ApplicationLifecycleService {

	private final TaskApplicationRepository apps;
	private final TaskMetricsRepository metrics;
	private final TaskAcceptanceCounterRepository acceptanceCounters;
	private final TaskRecommenderInvitationRepository recommenderInvitations;
	private final ReputationService reputationService;
	private final OutboxRepository outbox;
	private final TransactionalOperator transactions;
	private final FinanceEscrowClient finance;
	private final EngagementExtensionRepository extensions;
	private final SubmissionRepository submissions;
	private final ExperienceBenefitRepository benefits;
	private final EngagementExitRequestRepository exits;
	private final EngagementMilestoneService milestones;
	private final DisputeChecker disputes;
	private final long exitResponseSeconds;

	public ApplicationLifecycleService(TaskApplicationRepository apps, TaskMetricsRepository metrics,
			TaskAcceptanceCounterRepository acceptanceCounters,
			TaskRecommenderInvitationRepository recommenderInvitations, ReputationService reputationService,
			OutboxRepository outbox, TransactionalOperator transactions, FinanceEscrowClient finance,
			EngagementExtensionRepository extensions, SubmissionRepository submissions,
			ExperienceBenefitRepository benefits, EngagementExitRequestRepository exits,
			EngagementMilestoneService milestones, DisputeChecker disputes,
			@Value("${marketplace.engagement.exit-response-hours:72}") long exitResponseHours) {
		this.apps = apps;
		this.metrics = metrics;
		this.acceptanceCounters = acceptanceCounters;
		this.recommenderInvitations = recommenderInvitations;
		this.reputationService = reputationService;
		this.outbox = outbox;
		this.transactions = transactions;
		this.finance = finance;
		this.extensions = extensions;
		this.submissions = submissions;
		this.benefits = benefits;
		this.exits = exits;
		this.milestones = milestones;
		this.disputes = disputes;
		this.exitResponseSeconds = Math.max(1, exitResponseHours) * 3600L;
	}

	/**
	 * 推荐官报名（PRD「报名」+ GL-P1-TASK-001 报名截止）：任务须 published 且未截止、
	 * 等级达标、名额未满、一人一报；创建（冻结资金快照）+ 邀请标记 + outbox {@code ApplicationSubmitted}
	 * 同一事务。名额满 fail-fast 409。
	 */
	public Mono<TaskApplication> apply(Task task, Caller rec, String note) {
		if (!"published".equals(task.status())) {
			// #26 D9：closed/cancelled 文案单独拆分（原统一「任务当前不可报名」），其余状态维持原文案
			String message = "closed".equals(task.status())
					? "任务已关闭，无法报名"
					: "cancelled".equals(task.status()) ? "任务已取消，无法报名" : "任务当前不可报名";
			return fail(409, message);
		}
		// GL-P1-TASK-001 Stage 1：报名截止（PRD「指定时间」）。已截止 → 不接受新报名；
		// 既有 pending/accepted/履约不受影响（D-03 未决，不动 accept/confirm/结算）。
		if (task.applicationDeadline() != null && task.applicationDeadline().isBefore(Instant.now())) {
			return fail(409, "报名已截止");
		}
		return reputationService.snapshot(rec.accountId())
				.flatMap(snapshot -> snapshot.evaluation().effectiveLevel().number() < task.minRecommenderLevel()
						? Mono.<TaskApplication>error(new MarketplaceException(403, "当前等级不满足任务报名要求"))
						: slotsFull(task).flatMap(full -> full
								? Mono.<TaskApplication>error(new MarketplaceException(409, "名额已满"))
								: apps.findByTaskAndRecommender(task.id(), rec.accountId())
										.<TaskApplication>flatMap(
												existing -> Mono.error(new MarketplaceException(409, "已报名该任务")))
										.switchIfEmpty(transactions.transactional(apps
												.create(task.id(), rec.accountId(), note, TaskFunds.bountyOrZero(task),
														TaskFunds.freebieDepositOrZero(task))
												.switchIfEmpty(Mono.error(new MarketplaceException(409, "已报名该任务")))
												.flatMap(
														created -> recommenderInvitations
																.markApplied(task.id(), rec.accountId())
																.then(outbox.append(ApplicationEvents.envelope(
																		"ApplicationSubmitted", created,
																		task.ownerAccountId())))
																.thenReturn(created))))));
	}

	/** 商家拒绝 pending 报名：状态迁移 + outbox {@code ApplicationRejected}。 */
	public Mono<TaskApplication> reject(Task task, TaskApplication app, Caller merchant) {
		return apps.reject(app.id(), task.id(), merchant.accountId()).switchIfEmpty(fail(409, "该报名已处理"))
				.flatMap(rejected -> outbox
						.append(ApplicationEvents.envelope("ApplicationRejected", rejected, task.ownerAccountId()))
						.thenReturn(rejected));
	}

	/** 任务书 #27：batch-reject 单项。仅 pending 可处理；逐项独立，允许部分成功。 */
	public Mono<BatchItemResult> batchRejectItem(Task task, Caller merchant, String appId) {
		return apps.findById(appId).filter(app -> app.taskId().equals(task.id()))
				.filter(app -> ApplicationStatus.PENDING.dbValue().equals(app.status()))
				.flatMap(app -> apps.reject(appId, task.id(), merchant.accountId()).flatMap(rejected -> outbox
						.append(ApplicationEvents.envelope("ApplicationRejected", rejected, task.ownerAccountId()))
						.thenReturn(BatchItemResult.ofOutcome(appId, "rejected")))
						.switchIfEmpty(Mono.just(BatchItemResult.failed(appId, "该报名已处理"))))
				.switchIfEmpty(Mono.defer(() -> apps.findById(appId).filter(app -> app.taskId().equals(task.id()))
						.flatMap(app -> Mono.just(BatchItemResult.failed(appId, "该报名已处理")))
						.switchIfEmpty(Mono.just(BatchItemResult.failed(appId, "报名不存在")))));
	}

	/**
	 * 推荐官撤销本人 pending/reconsent 报名：withdraw WHERE 烧入 recommender（HLD 7.4）+ outbox
	 * 同一事务。
	 */
	public Mono<TaskApplication> withdraw(TaskApplication app, Task task, Caller rec) {
		return transactions.transactional(apps.withdraw(app.id(), task.id(), rec.accountId())
				.switchIfEmpty(fail(409, "该报名已处理"))
				.flatMap(withdrawn -> outbox
						.append(ApplicationEvents.envelope("ApplicationWithdrawn", withdrawn, task.ownerAccountId()))
						.thenReturn(withdrawn)));
	}

	/**
	 * 任务书 #90 C90-02 D90-07：推荐官重新确认修订后的条款。reconsent → pending， V53 trigger 清
	 * reconsent_required 并把条款快照（task_version_at_apply / terms_snapshot_json）
	 * 刷新到当前任务版本——重确认后商家即可按现行条款接受。WHERE 烧入 recommender。
	 */
	public Mono<TaskApplication> reconfirmTerms(Task task, TaskApplication app, Caller rec) {
		return transactions.transactional(apps.reconsent(app.id(), task.id(), rec.accountId())
				.switchIfEmpty(fail(409, "该报名无需重新确认"))
				.flatMap(confirmed -> outbox
						.append(ApplicationEvents.envelope("ApplicationReconsented", confirmed, task.ownerAccountId()))
						.thenReturn(confirmed)));
	}

	// ---------- 任务书 #96 C96-01：无责退出 / 延期申请与批准 ----------

	/**
	 * 推荐官无责退出（§5.1）：accepted + 政策版内 + 未确认 + 无任何提交 + 无已确认里程碑 + 体验权益未兑现。
	 * 资金两腿按来源释放（零补偿：赏金释放返商家、押金原路退推荐官）；终态 withdrawn + exit_kind=no_fault ——声誉聚合本就把
	 * withdrawn 排除在完成率分母外（TC96-003 无需改口径）。名额同事务回收。 任务书 #96
	 * C96-03（TC96-014）：已兑现体验（已消费）的推荐官不履约走协商/争议或超时终结， 不得无责退出把押金带走——未消费退出与已消费不履约分开。
	 */
	public Mono<TaskApplication> exitNoFault(Task task, TaskApplication app, Caller rec) {
		Mono<TaskApplication> guarded = switch (precondition(app)) {
			case OK -> Mono.empty();
			case NOT_ACCEPTED -> fail(409, "该报名已处理");
			case ALREADY_CONFIRMED -> fail(409, "该履约已确认，无法退出");
			case LEGACY -> fail(409, "该报名不受交付期限规则约束，无法无责退出");
		};
		return guarded.then(submissions.findByApplication(app.id()).hasElements().flatMap(hasSubmission -> {
			if (hasSubmission) {
				return fail(409, "已提交履约凭证，退出请走协商/争议");
			}
			return benefits.findByApplication(app.id()).map(ExperienceBenefit::consumed).defaultIfEmpty(false)
					.flatMap(consumed -> {
						if (consumed) {
							return fail(409, "体验已兑现，退出请走协商/争议");
						}
						// Finance remains outside the local transaction. Once its stable operation key
						// has
						// been replayed successfully, all local terminal facts must commit or roll back
						// together.
						return fundsRelease(task, app)
								.then(transactions.transactional(apps.exitNoFault(app.id(), task.id(), rec.accountId())
										.switchIfEmpty(fail(409, "当前状态不可无责退出")).flatMap(exited -> releaseSlot(task.id())
												// 任务书 #97 D97-05：任一终态先到（无责退出）→ 残留协商申请自动 cancelled。
												.then(exits.cancelPendingByApplication(app.id()))
												.then(outbox.append(ApplicationEvents.envelope(
														"ApplicationExitedNoFault", exited, task.ownerAccountId())))
												.thenReturn(exited))));
					});
		}));
	}

	/**
	 * 推荐官发起延期申请（§6 /extend 申请侧）：交付期内的报名（accepted+政策版内+未确认+未退出）可申请， 同一报名同时至多一条待审（V56
	 * 部分唯一索引兜底并发）。批准前不改动任何截止。
	 */
	public Mono<EngagementExtensionRepository.EngagementExtension> requestExtension(Task task, TaskApplication app,
			Caller rec, Integer days, String reason) {
		Mono<TaskApplication> guarded = switch (precondition(app)) {
			case OK -> Mono.empty();
			case NOT_ACCEPTED -> fail(409, "该报名已处理");
			case ALREADY_CONFIRMED -> fail(409, "该履约已确认，无需延期");
			case LEGACY -> fail(409, "该报名不受交付期限规则约束，无法申请延期");
		};
		if (days == null || days <= 0) {
			return fail(400, "延期天数必须为正整数");
		}
		if (days > 365) {
			return fail(400, "延期天数不能超过 365 天");
		}
		final String normalizedReason = reason == null || reason.isBlank() ? null : reason.trim();
		return guarded.then(transactions.transactional(extensions
				.createPending(app.id(), rec.accountId(), days, normalizedReason).switchIfEmpty(fail(409, "已有待处理的延期申请"))
				.flatMap(created -> outbox
						.append(extensionEnvelope("DeliveryExtensionRequested", task, app, created, null))
						.thenReturn(created))));
	}

	/**
	 * 商家批准/拒绝延期（§6 /extend 决定侧）：决定与 deadline 后移同事务（guarded 单边胜出）； 批准时清空看门狗派发标记 →
	 * 派发器按新截止补启 workflow（旧 workflow 被行级守卫 abort）。
	 */
	public Mono<EngagementExtensionRepository.EngagementExtension> decideExtension(Task task, TaskApplication app,
			Caller merchant, boolean approved) {
		Mono<TaskApplication> guarded = switch (precondition(app)) {
			case OK -> Mono.empty();
			case NOT_ACCEPTED -> fail(409, "该报名已处理");
			case ALREADY_CONFIRMED -> fail(409, "该履约已确认，延期申请已无意义");
			case LEGACY -> fail(409, "该报名不受交付期限规则约束");
		};
		return guarded.then(transactions.transactional(extensions.decide(app.id(), approved, merchant.accountId())
				.switchIfEmpty(fail(409, "无待处理的延期申请")).flatMap(decision -> {
					if (!approved) {
						return outbox.append(extensionEnvelope("DeliveryExtensionRejected", task, app, decision, null))
								.thenReturn(decision);
					}
					return apps.extendDeliveryDeadline(app.id(), task.id(), decision.days() * 86400L)
							.switchIfEmpty(
									fail(409, "报名状态已变，延期无法生效"))
							.flatMap(extended -> outbox.append(extensionEnvelope("DeliveryExtensionApproved", task,
									extended, decision, extended.deliveryDeadlineAt())).thenReturn(decision));
				})));
	}

	/** 退出/延期共同的进入门槛（SQL 守卫另作权威兜底）。 */
	private enum ExitPrecondition {
		OK, NOT_ACCEPTED, ALREADY_CONFIRMED, LEGACY
	}

	private ExitPrecondition precondition(TaskApplication app) {
		if (!ApplicationStatus.ACCEPTED.dbValue().equals(app.status())) {
			return ExitPrecondition.NOT_ACCEPTED;
		}
		if (app.confirmedAt() != null) {
			return ExitPrecondition.ALREADY_CONFIRMED;
		}
		if (!app.underDeliveryPolicy()) {
			return ExitPrecondition.LEGACY;
		}
		return ExitPrecondition.OK;
	}

	/** 无责退出的资金释放：押金退推荐官（无责）、赏金释放返商家（零补偿）。 */
	private Mono<Void> fundsRelease(Task task, TaskApplication app) {
		Mono<Void> freebieLeg = app.freebieDepositCents() > 0
				? finance.freebieRefund(task.organizationId(), app.id())
				: Mono.empty();
		Mono<Void> bountyLeg = app.bountyCents() > 0 ? finance.release(task.organizationId(), app.id()) : Mono.empty();
		return freebieLeg.then(bountyLeg);
	}

	/** 名额回收：counter 归零守卫防下溢（同 accept Saga 补偿惯例）。 */
	private Mono<Void> releaseSlot(String taskId) {
		return acceptanceCounters.release(taskId).filter(Boolean::booleanValue)
				.switchIfEmpty(Mono.error(new IllegalStateException("acceptance counter underflow"))).then();
	}

	// ---------- 任务书 #97 C97-03：协商退出状态机（D97-04/05/07） ----------

	/** 协商退出确认结果：终态后的报名行 + 已确认的申请行。 */
	public record NegotiatedExitOutcome(TaskApplication application,
			EngagementExitRequestRepository.EngagementExitRequest request,
			EngagementMilestoneService.SettlementBreakdown breakdown) {
	}

	/**
	 * 双方对等发起协商退出（§6 /exit kind=negotiated）：reason 必填；合作须进行中（accepted）；
	 * 非套餐推广（按订单结算无内容退出）；开放争议先走争议（D97-07）；同一报名同时至多一个开放申请 （V59 partial unique，并发双开 →
	 * 409）。响应窗 = now + exit-response-hours（缺省 72h）。
	 * 协商退出不暂停任何既有计时（原交付期限、审稿窗口照常，D97-05）。
	 */
	public Mono<EngagementExitRequestRepository.EngagementExitRequest> requestNegotiatedExit(Task task,
			TaskApplication app, Caller initiator, String initiatedRole, String reason) {
		if (reason == null || reason.isBlank()) {
			return fail(400, "协商退出原因必填");
		}
		String normalized = reason.trim();
		if (normalized.length() < 5 || normalized.length() > 500) {
			return fail(400, "协商退出原因长度须在 5 到 500 字之间");
		}
		if (!ApplicationStatus.ACCEPTED.dbValue().equals(app.status())) {
			return fail(409, "合作不在进行中，无法发起协商退出");
		}
		if (task.isCommercePromotion()) {
			return fail(409, "套餐推广按订单结算，无需退出履约");
		}
		return Mono.fromCallable(() -> disputes.hasOpenDispute(task.organizationId(), app.id()))
				.subscribeOn(Schedulers.boundedElastic()).flatMap(
						hasOpen -> hasOpen
								? fail(409, "该合作存在未决争议，请先完成争议处理")
								: exits.insertPending(app.id(), task.id(), initiator.accountId(), initiatedRole,
										normalized, Instant.now().plusSeconds(exitResponseSeconds))
										.switchIfEmpty(fail(409, "已有待处理的协商退出申请"))
										.flatMap(created -> outbox.append(
												exitEnvelope("EngagementExitRequested", task, app, created, null))
												.thenReturn(created)));
	}

	/**
	 * 对方响应（§6 /exit-requests/{id}/confirm|reject）：仅<b>相反业务方</b>可操作（审查修复 02 / R05）。
	 * responderParty 由 Controller 经资源归属 + Identity 权威授权解析传入（不信任请求体 role）； 与
	 * request.initiatedRole 同侧（含发起账号本人、同商家其他管理者、其他推荐官）→ 403， 该校验先于 confirmed
	 * 幂等续传分支执行——重入不得绕过授权。账号比较仅保留「原发起账号」约束。 拒绝 → 申请关闭、合作继续。确认 → 三段编排（终态竞态单边胜出）：
	 * ①事务一：抢 application 终态（withdrawn+exit_kind=negotiated）+ 抢申请 confirmed + 名额回收，
	 * 任一 0 行即回滚（对方已终结 → 残留申请收口 cancelled 后 409）； ②资金腿（事务外幂等）：有确认里程碑 → capture 里程碑金额
	 * + release 余款；无 → 零补偿全额释放 （同开工前取消）；押金原路退推荐官； ③事务二：里程碑金额回填 + outbox 结算事件（确定性
	 * eventId，重试幂等）。 确认后资金腿失败的续传：请求已 confirmed 时重入本方法直接续跑 ②③（幂等收敛）。
	 */
	public Mono<EngagementExitRequestRepository.EngagementExitRequest> respondNegotiatedExit(Task task,
			TaskApplication app, String exitId, Caller responder, String responderParty, boolean approve) {
		return exits.findById(exitId).switchIfEmpty(fail(404, "协商退出申请不存在")).flatMap(request -> {
			if (!request.applicationId().equals(app.id())) {
				return fail(404, "协商退出申请不存在");
			}
			// R05：业务方判定——同侧（responderParty == initiatedRole）或原发起账号本人一律 403；
			// 覆盖 confirm、reject 与 confirmed 后重入（先于幂等分支，不因已 confirmed 而放宽授权）。
			if (request.initiatedByAccountId().equals(responder.accountId())
					|| request.initiatedRole().equals(responderParty)) {
				return fail(403, "双方确认制：需由对方业务方确认（发起方及同侧账号无权响应）");
			}
			if (!"recommender".equals(responderParty) && !"merchant".equals(responderParty)) {
				return fail(403, "无法解析响应方业务身份，拒绝处理");
			}
			if ("confirmed".equals(request.status())) {
				// 续传（事务一已胜出，资金腿重试收敛）——仅确认路径有意义。
				return approve ? settleNegotiated(task, app, request).thenReturn(request) : fail(409, "该申请已处理");
			}
			if (!request.pending()) {
				return fail(409, "该申请已处理");
			}
			if (!approve) {
				return transactions.transactional(
						exits.respond(exitId, false, responder.accountId()).switchIfEmpty(fail(409, "该申请已处理"))
								.flatMap(rejected -> outbox
										.append(exitEnvelope("EngagementExitRejected", task, app, rejected, null))
										.thenReturn(rejected)));
			}
			Mono<NegotiatedExitOutcome> claimed = transactions.transactional(apps.exitNegotiated(app.id(), task.id())
					.flatMap(finalized -> exits.respond(exitId, true, responder.accountId())
							.switchIfEmpty(fail(409, "该申请已被处理"))
							.flatMap(confirmed -> releaseSlot(task.id())
									.thenReturn(new NegotiatedExitOutcome(finalized, confirmed, null))))
					.switchIfEmpty(Mono.defer(
							() -> exits.cancelPendingByApplication(app.id()).then(fail(409, "合作已被终结，协商退出申请自动取消")))));
			return claimed
					.flatMap(outcome -> settleNegotiated(task, app, outcome.request()).thenReturn(outcome.request()));
		});
	}

	/** 发起方撤回 pending 申请（§6 /exit-requests/{id}/cancel）：合作照常继续。 */
	public Mono<EngagementExitRequestRepository.EngagementExitRequest> cancelNegotiatedExit(Task task,
			TaskApplication app, String exitId, Caller initiator) {
		return exits.findById(exitId).switchIfEmpty(fail(404, "协商退出申请不存在")).flatMap(request -> {
			if (!request.applicationId().equals(app.id())) {
				return fail(404, "协商退出申请不存在");
			}
			if (!request.initiatedByAccountId().equals(initiator.accountId())) {
				return fail(403, "仅发起方可撤回协商退出申请");
			}
			return transactions.transactional(
					exits.cancelByInitiator(exitId, initiator.accountId()).switchIfEmpty(fail(409, "仅待响应的申请可撤回"))
							.flatMap(cancelled -> outbox
									.append(exitEnvelope("EngagementExitCancelled", task, app, cancelled, null))
									.thenReturn(cancelled)));
		});
	}

	/** 双方申请列表（§6 GET /exit-requests）：行 + 预演结算金额（服务端算，前端不复算，§5.3）。 */
	public Mono<List<Map<String, Object>>> listNegotiatedExits(Task task, TaskApplication app) {
		return exits.listByApplication(app.id()).concatMap(
				request -> milestones.computeSettlement(app, task).map(breakdown -> exitBody(request, breakdown)))
				.collectList();
	}

	/** 协商退出的结算腿（C96-02 settleCancelledEngagement 同构）：无里程碑=零补偿全额释放（同开工前取消）。 */
	private Mono<Void> settleNegotiated(Task task, TaskApplication app,
			EngagementExitRequestRepository.EngagementExitRequest request) {
		return milestones.computeSettlement(app, task).flatMap(breakdown -> {
			Mono<Void> bountyLeg;
			if (app.bountyCents() > 0 && !breakdown.isEmpty()) {
				bountyLeg = finance
						.captureVerified(task.organizationId(), app.id(), app.bountyCents(), app.recommenderAccountId(),
								breakdown.totalCents())
						.flatMap(outcome -> outcome.captured()
								? finance.release(task.organizationId(), app.id())
								: Mono.error(new FinanceEscrowException("negotiated exit capture needs reconciliation: "
										+ outcome.reconciliationReason())));
			} else if (app.bountyCents() > 0) {
				bountyLeg = finance.release(task.organizationId(), app.id());
			} else {
				bountyLeg = Mono.empty();
			}
			Mono<Void> freebieLeg = app.freebieDepositCents() > 0
					? finance.freebieRefund(task.organizationId(), app.id())
					: Mono.empty();
			return freebieLeg.then(bountyLeg)
					.then(transactions.transactional(milestones.recordSettlementAmounts(app, breakdown).then(
							outbox.append(exitEnvelope("EngagementExitedNegotiated", task, app, request, breakdown)))));
		});
	}

	/** 协商退出行 → 响应体：申请事实 + 结算预演（每行都带——退出确认前预演=按当前已确认里程碑算）。 */
	private static Map<String, Object> exitBody(EngagementExitRequestRepository.EngagementExitRequest request,
			EngagementMilestoneService.SettlementBreakdown breakdown) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", request.id());
		body.put("applicationId", request.applicationId());
		body.put("taskId", request.taskId());
		body.put("initiatedRole", request.initiatedRole());
		body.put("reason", request.reason());
		body.put("status", request.status());
		body.put("respondDeadlineAt", request.respondDeadlineAt().toString());
		if (request.respondedAt() != null) {
			body.put("respondedAt", request.respondedAt().toString());
		}
		body.put("createdAt", request.createdAt().toString());
		body.put("settlementPreview", breakdown.toBody());
		return body;
	}

	/** 协商退出事件信封：确定性 event_id（type:exitId）保重试 exactly-once；结算事件引用里程碑 id 与金额。 */
	private EventEnvelope exitEnvelope(String eventType, Task task, TaskApplication app,
			EngagementExitRequestRepository.EngagementExitRequest request,
			EngagementMilestoneService.SettlementBreakdown breakdown) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("taskId", task.id());
		payload.put("applicationId", app.id());
		payload.put("recommenderAccountId", app.recommenderAccountId());
		payload.put("exitRequestId", request.id());
		payload.put("initiatedRole", request.initiatedRole());
		payload.put("reason", request.reason());
		payload.put("status", request.status());
		payload.put("respondDeadlineAt", request.respondDeadlineAt().toString());
		if (breakdown != null) {
			payload.put("settlement", breakdown.toBody());
		}
		payload.put("taskOwnerId", task.ownerAccountId());
		String eventId = UUID.nameUUIDFromBytes((eventType + ":" + request.id()).getBytes(StandardCharsets.UTF_8))
				.toString();
		return new EventEnvelope(eventId, eventType, "TaskApplication", app.id(), 1, Instant.now(), null, payload);
	}

	/** 延期事件信封：extensionId/days/reason + 批准时的新交付截止。确定性 event_id 保 exactly-once。 */
	private EventEnvelope extensionEnvelope(String eventType, Task task, TaskApplication app,
			EngagementExtensionRepository.EngagementExtension extension, Instant newDeadline) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("taskId", task.id());
		payload.put("applicationId", app.id());
		payload.put("recommenderAccountId", app.recommenderAccountId());
		payload.put("extensionId", extension.id());
		payload.put("days", extension.days());
		if (extension.reason() != null) {
			payload.put("reason", extension.reason());
		}
		if (newDeadline != null) {
			payload.put("deliveryDeadlineAt", newDeadline.toString());
		}
		payload.put("taskOwnerId", task.ownerAccountId());
		String eventId = UUID.nameUUIDFromBytes((eventType + ":" + extension.id()).getBytes(StandardCharsets.UTF_8))
				.toString();
		return new EventEnvelope(eventId, eventType, "TaskApplication", app.id(), 1, Instant.now(), null, payload);
	}

	/** 非 owner 视图：仅本人报名行（不相干的人拿空列表，不泄露信息）。 */
	public Mono<List<Map<String, Object>>> ownApplications(String taskId, Caller caller, String status,
			Instant createdAfter, Instant createdBefore, int limit) {
		return apps.findByTaskId(taskId, status, createdAfter, createdBefore, limit)
				.filter(a -> caller.accountId().equals(a.recommenderAccountId())).map(ApplicationBodies::toBody)
				.collectList();
	}

	// ---------- 任务书 #90 C90-05：报名列表 keyset 分页（items / nextCursor / hasMore）
	// ----------

	/** 分页响应体：行已转 body，游标对客户端不透明。 */
	public record ApplicationListPage(List<Map<String, Object>> items, String nextCursor, boolean hasMore) {
	}

	/** 非 owner 视图：仅本人报名行（本人过滤进 SQL，在 LIMIT 之前——§5 规则 5）。 */
	public Mono<ApplicationListPage> ownApplicationsPage(String taskId, Caller caller, String status,
			Instant createdAfter, Instant createdBefore, String cursor, int limit) {
		return apps.findByTaskIdPaged(taskId, status, caller.accountId(), createdAfter, createdBefore, cursor, limit)
				.map(page -> new ApplicationListPage(page.items().stream().map(ApplicationBodies::toBody).toList(),
						page.nextCursor(), page.hasMore()));
	}

	/**
	 * owner 视图：DB 按 (created_at, id) 倒序分页后，页内附声誉快照并按权重降序展示 （分页序以申请时间为准，权重是页内展示序——§5
	 * 分页规则优先）。
	 */
	public Mono<ApplicationListPage> rankedApplicationsPage(String taskId, String status, Instant createdAfter,
			Instant createdBefore, String cursor, int limit) {
		return apps.findByTaskIdPaged(taskId, status, null, createdAfter, createdBefore, cursor, limit)
				.flatMap(page -> page.items().isEmpty()
						? Mono.just(toRankedPage(page, Map.of()))
						: reputationService
								.snapshots(page.items().stream().map(TaskApplication::recommenderAccountId).toList())
								.map(snapshots -> toRankedPage(page, snapshots)));
	}

	private ApplicationListPage toRankedPage(TaskApplicationRepository.ApplicationPage page,
			Map<String, ReputationSnapshot> snapshots) {
		List<Map<String, Object>> items = page.items().stream()
				.map(app -> new RankedApplication(app, snapshots.get(app.recommenderAccountId())))
				.sorted((left, right) -> {
					int byWeight = Integer.compare(right.snapshot().evaluation().taskPriorityWeight(),
							left.snapshot().evaluation().taskPriorityWeight());
					if (byWeight != 0)
						return byWeight;
					int byCreatedAt = left.application().createdAt().compareTo(right.application().createdAt());
					if (byCreatedAt != 0)
						return byCreatedAt;
					return left.application().id().compareTo(right.application().id());
				}).map(ranked -> ApplicationBodies.ranked(ranked.application(), ranked.snapshot())).toList();
		return new ApplicationListPage(items, page.nextCursor(), page.hasMore());
	}

	/** owner 视图：全部报名按声誉权重降序（同权重按创建时间/ id 稳定排序），行附声誉快照三字段。 */
	public Mono<List<Map<String, Object>>> rankedApplications(String taskId, String status, Instant createdAfter,
			Instant createdBefore, int limit) {
		return apps.findByTaskId(taskId, status, createdAfter, createdBefore, limit).collectList()
				.flatMap(applications -> reputationService
						.snapshots(applications.stream().map(TaskApplication::recommenderAccountId).toList())
						.map(snapshots -> applications.stream()
								.map(app -> new RankedApplication(app, snapshots.get(app.recommenderAccountId())))
								.sorted((left, right) -> {
									int byWeight = Integer.compare(right.snapshot().evaluation().taskPriorityWeight(),
											left.snapshot().evaluation().taskPriorityWeight());
									if (byWeight != 0)
										return byWeight;
									int byCreatedAt = left.application().createdAt()
											.compareTo(right.application().createdAt());
									if (byCreatedAt != 0)
										return byCreatedAt;
									return left.application().id().compareTo(right.application().id());
								}).map(ranked -> ApplicationBodies.ranked(ranked.application(), ranked.snapshot()))
								.toList()));
	}

	/** 任务进度统计（list 的 stats / summary 端点共用；与过滤行分离，分页不扭曲总量）。 */
	public Mono<Map<String, Object>> taskProgress(String taskId, Task task) {
		return metrics.findProgressByTaskIds(List.of(taskId)).next().defaultIfEmpty(TaskProgress.empty(taskId))
				.map(facts -> {
					Map<String, Object> stats = new LinkedHashMap<>();
					stats.put("total", facts.totalApplications());
					stats.put("pending", facts.pendingApplications());
					stats.put("reserving", facts.reservingApplications());
					stats.put("accepted", facts.acceptedApplications());
					stats.put("rejected", facts.rejectedApplications());
					stats.put("withdrawn", facts.withdrawnApplications());
					stats.put("refunded", facts.refundedApplications());
					stats.put("occupiedSlots", facts.occupiedSlots());
					stats.put("maxSlots", task.maxSlots());
					stats.put("remainingSlots",
							task.maxSlots() == null ? null : Math.max(0, task.maxSlots() - facts.occupiedSlots()));
					stats.put("submittedDeliverables", facts.submittedDeliverables());
					stats.put("confirmedDeliverables", facts.confirmedDeliverables());
					stats.put("settledEngagements", facts.settledEngagements());
					stats.put("reservedBountyCents", facts.reservedBountyCents());
					stats.put("settledBountyCents", facts.settledBountyCents());
					return stats;
				});
	}

	/** 名额是否已满：reserving 与 accepted 都通过事务 counter 占位。 */
	private Mono<Boolean> slotsFull(Task task) {
		Integer max = task.maxSlots();
		if (max == null) {
			return Mono.just(false);
		}
		return acceptanceCounters.occupied(task.id()).map(occupied -> occupied >= max);
	}

	private record RankedApplication(TaskApplication application, ReputationSnapshot snapshot) {
	}

	private static <T> Mono<T> fail(int status, String message) {
		return Mono.error(new MarketplaceException(status, message));
	}
}
