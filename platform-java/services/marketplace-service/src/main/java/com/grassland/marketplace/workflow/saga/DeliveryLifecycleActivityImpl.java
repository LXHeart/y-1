package com.grassland.marketplace.workflow.saga;

import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.taskcatalog.SubmissionRepository;
import com.grassland.marketplace.taskcatalog.Task;
import com.grassland.marketplace.taskcatalog.TaskApplication;
import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
import com.grassland.marketplace.taskcatalog.TaskRepository;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import io.temporal.spring.boot.ActivityImpl;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 交付看门狗活动实现（任务书 #96 C96-01）。照 {@link ConfirmationActivityImpl} 惯例：
 * 每次执行前重验行级状态，guard 烧进条件 UPDATE（0 行 = 已被并发路径赢走 → abort），outbox 与领域写同事务， 跨服务
 * finance 调用留事务外（幂等 + 重试收敛）。
 *
 * <p>
 * 有责终结的资金方向（§4.1.4 释放余款）：bounty 腿 release 返商家；freebie 腿 freebieCompensate
 * （押金判商家——finance 既有「未达标/商家获判」语义）。无责退出（退推荐官）走
 * {@code ApplicationLifecycleService#exitNoFault}，两条路径共用行级守卫互斥。
 */
@Component
@ActivityImpl(workers = ApplicationReservationWorkflowImpl.TASK_QUEUE)
public class DeliveryLifecycleActivityImpl implements DeliveryLifecycleActivity {

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DeliveryLifecycleActivityImpl.class);

	private final TaskApplicationRepository apps;
	private final TaskRepository tasks;
	private final SubmissionRepository submissions;
	private final OutboxRepository outbox;
	private final FinanceEscrowClient finance;
	private final com.grassland.marketplace.taskcatalog.TaskAcceptanceCounterRepository counters;
	private final com.grassland.marketplace.milestone.EngagementMilestoneService milestoneService;
	private final TransactionalOperator transactions;
	private final com.grassland.marketplace.taskcatalog.EngagementExitRequestRepository exits;
	private final com.grassland.marketplace.taskcatalog.ApplicationMutationGuard mutationGuard;

	public DeliveryLifecycleActivityImpl(TaskApplicationRepository apps, TaskRepository tasks,
			SubmissionRepository submissions, OutboxRepository outbox, FinanceEscrowClient finance,
			com.grassland.marketplace.taskcatalog.TaskAcceptanceCounterRepository counters,
			com.grassland.marketplace.milestone.EngagementMilestoneService milestoneService,
			TransactionalOperator transactions,
			com.grassland.marketplace.taskcatalog.EngagementExitRequestRepository exits,
			com.grassland.marketplace.taskcatalog.ApplicationMutationGuard mutationGuard) {
		this.apps = apps;
		this.tasks = tasks;
		this.submissions = submissions;
		this.outbox = outbox;
		this.finance = finance;
		this.counters = counters;
		this.milestoneService = milestoneService;
		this.transactions = transactions;
		this.exits = exits;
		this.mutationGuard = mutationGuard;
	}

	@Override
	public void notifyDeliveryExpiring(DeliveryDeadlineInput input) {
		TaskApplication app = apps.findById(input.applicationId()).block();
		if (app == null || !"accepted".equals(app.status()) || app.confirmedAt() != null || app.exitedAt() != null) {
			return; // 窗口已失效
		}
		boolean hasSubmission = submissions.findByApplication(app.id()).hasElements().block();
		if (hasSubmission) {
			return; // 已进凭证/确认阶段，交付期已履行
		}
		Task task = tasks.findById(app.taskId()).block();
		String taskOwnerId = task == null ? null : task.ownerAccountId();
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("taskId", app.taskId());
		payload.put("applicationId", app.id());
		payload.put("recommenderAccountId", app.recommenderAccountId());
		payload.put("deliveryDeadlineAt",
				app.deliveryDeadlineAt() == null ? null : app.deliveryDeadlineAt().toString());
		payload.put("remedyDeadlineAt", app.remedyDeadlineAt() == null ? null : app.remedyDeadlineAt().toString());
		if (taskOwnerId != null) {
			payload.put("taskOwnerId", taskOwnerId);
		}
		String eventId = UUID.nameUUIDFromBytes(("DeliveryDeadlineExpiring:" + app.id() + ":"
				+ (app.deliveryDeadlineAt() == null ? "" : app.deliveryDeadlineAt().getEpochSecond()))
				.getBytes(StandardCharsets.UTF_8)).toString();
		outbox.append(new EventEnvelope(eventId, "DeliveryDeadlineExpiring", "TaskApplication", app.id(), 1,
				Instant.now(), null, payload)).block();
	}

	@Override
	public DeliveryOutcome terminateDeliveryTimeout(DeliveryDeadlineInput input) {
		TaskApplication first = apps.findById(input.applicationId()).block();
		if (first == null) {
			return DeliveryOutcome.aborted();
		}
		// 任务书 #103 C103-02（R01 家族）：先 claim 后资金。此前资金腿先于 guarded 终结执行，
		// 与无责/协商退出并发时可能双方动钱。现在终态在 task → application 父行锁事务内先落定，
		// 资金腿只在 claim 提交后按原经济键幂等执行；前次 claim 已提交而资金失败（held）时，
		// Temporal 重试走 resume 分支直接重放资金腿（键幂等，不重复落账）。
		boolean resume = "refunded".equals(first.status()) && "timeout".equals(first.exitKind());
		if (!resume && (!"accepted".equals(first.status()) || first.confirmedAt() != null
				|| first.exitedAt() != null)) {
			return DeliveryOutcome.aborted();
		}
		Task task = tasks.findById(first.taskId()).block();
		if (task == null) {
			return DeliveryOutcome.aborted();
		}
		String taskOwnerId = task.ownerAccountId();
		final TaskApplication app = first;
		if (!resume) {
			Boolean claimed = mutationGuard
					.withLockedApplication(task.id(), app.id(), (lockedTask, lockedApp) -> {
						if (!"accepted".equals(lockedApp.status()) || lockedApp.confirmedAt() != null
								|| lockedApp.exitedAt() != null) {
							return Mono.just(false); // 延期/确认/退出/取消抢先 → 单边胜出
						}
						return submissions.findByApplication(lockedApp.id()).hasElements().flatMap(hasSubmission -> {
							if (hasSubmission) {
								return Mono.just(false); // 补救窗内已交付 → 交付期已履行，进确认窗口
							}
							// 任务书 #96 C96-02（TC96-002）：有责终结按锁内已确认里程碑冻结结算额；
							// guarded 终结（remedy_deadline_at 守卫保留为权威）+ 名额回收 + 残留协商申请
							// 收口 + 里程碑金额回填 + outbox 同一锁事务提交（锁内链路保持非阻塞）。
							return milestoneService.computeSettlement(lockedApp, lockedTask)
									.flatMap(breakdown -> transactions.transactional(
									apps.markDeliveryTimedOut(lockedApp.id(), lockedTask.id())
											.flatMap(done -> counters.release(lockedTask.id())
													.filter(Boolean::booleanValue)
													.switchIfEmpty(Mono.error(
															new IllegalStateException("acceptance counter underflow")))
													.then(exits.cancelPendingByApplication(lockedApp.id()))
													.then(milestoneService.recordSettlementAmounts(lockedApp, breakdown))
													.then(outbox.append(terminatedEnvelope(lockedTask, done, taskOwnerId,
															breakdown)))
													.thenReturn(done)))
									.hasElement());
						});
					}).block();
			if (claimed == null || !claimed) {
				return DeliveryOutcome.aborted();
			}
		}
		// 资金腿（claim 提交后；capture 里程碑金额给推荐官、余款释放；无里程碑 → 全额释放；
		// capture 对账未清 → held 留待重试，resume 分支重放同键收敛）。
		var breakdown = milestoneService.computeSettlement(app).block();
		java.util.List<Mono<Void>> fundLegs = new java.util.ArrayList<>();
		if (app.freebieDepositCents() > 0) {
			fundLegs.add(finance.freebieCompensate(task.organizationId(), app.id()));
		}
		if (app.bountyCents() > 0) {
			if (breakdown != null && !breakdown.isEmpty()) {
				fundLegs.add(finance
						.captureVerified(task.organizationId(), app.id(), app.bountyCents(), app.recommenderAccountId(),
								breakdown.totalCents())
						.flatMap(outcome -> outcome.captured()
								? finance.release(task.organizationId(), app.id())
								: Mono.error(new com.grassland.marketplace.workflow.FinanceEscrowException(
										"timeout settlement capture needs reconciliation: "
												+ outcome.reconciliationReason()))));
			} else {
				fundLegs.add(finance.release(task.organizationId(), app.id()));
			}
		}
		try {
			Mono.when(fundLegs.toArray(Mono[]::new)).block();
		} catch (RuntimeException failure) {
			log.warn("delivery timeout settlement funds need reconciliation app={}", app.id(), failure);
			return new DeliveryOutcome("held", "settlement_capture_reconciliation");
		}
		return DeliveryOutcome.terminated();
	}

	private EventEnvelope terminatedEnvelope(Task task, TaskApplication app, String taskOwnerId,
			com.grassland.marketplace.milestone.EngagementMilestoneService.SettlementBreakdown breakdown) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("taskId", task.id());
		payload.put("applicationId", app.id());
		payload.put("recommenderAccountId", app.recommenderAccountId());
		payload.put("exitKind", app.exitKind());
		payload.put("exitedAt", app.exitedAt() == null ? null : app.exitedAt().toString());
		payload.put("reason", "delivery_timeout");
		if (breakdown != null && !breakdown.isEmpty()) {
			payload.put("settlement", breakdown.toBody());
		}
		if (taskOwnerId != null) {
			payload.put("taskOwnerId", taskOwnerId);
		}
		String eventId = UUID
				.nameUUIDFromBytes(("DeliveryTimeoutTerminated:" + app.id()).getBytes(StandardCharsets.UTF_8))
				.toString();
		return new EventEnvelope(eventId, "DeliveryTimeoutTerminated", "TaskApplication", app.id(), 1, Instant.now(),
				null, payload);
	}
}
