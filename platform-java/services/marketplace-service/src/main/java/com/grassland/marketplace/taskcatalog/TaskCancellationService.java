package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Component;

/**
 * Cancellation coordinator: commit cancellation first, then retryable finance
 * and atomic local settlement.
 */
@Component
public class TaskCancellationService {
	private final TaskRepository tasks;
	private final OutboxRepository outbox;
	private final TaskApplicationRepository apps;
	private final FinanceEscrowClient finance;
	private final TransactionalOperator transactions;
	private final com.grassland.marketplace.milestone.EngagementMilestoneService milestoneService;
	private final EngagementExitRequestRepository exits;
	private final TaskWritePolicy taskWritePolicy;
	private final TaskPromotionService taskPromotionService;

	public TaskCancellationService(TaskRepository tasks, OutboxRepository outbox, TaskApplicationRepository apps,
			FinanceEscrowClient finance, TransactionalOperator transactions,
			com.grassland.marketplace.milestone.EngagementMilestoneService milestoneService,
			EngagementExitRequestRepository exits, TaskWritePolicy taskWritePolicy,
			TaskPromotionService taskPromotionService) {
		this.tasks = tasks;
		this.outbox = outbox;
		this.apps = apps;
		this.finance = finance;
		this.transactions = transactions;
		this.milestoneService = milestoneService;
		this.exits = exits;
		this.taskWritePolicy = taskWritePolicy;
		this.taskPromotionService = taskPromotionService;
	}

	public Mono<Result> cancel(String id, TaskLifecycleRequest body, Caller caller) {
		return taskWritePolicy.loadManageableTask(id, caller, null).flatMap(owned -> {
			String status = owned.status();
			if (TaskStatus.CANCELLED.dbValue().equals(status)) {
				// 幂等重放：补齐可能遗漏的收尾（退款/终态化两侧幂等），计数按现状重算
				return finalizeCancellation(owned, 0).map(counts -> result(owned, counts));
			}
			if (!TaskStatus.DRAFT.dbValue().equals(status) && !TaskStatus.PUBLISHED.dbValue().equals(status)
					&& !TaskStatus.PENDING_REVIEW.dbValue().equals(status)) {
				return Mono.<Result>error(new MarketplaceException(409, "任务已结束，不可取消"));
			}
			return transactions
					.transactional(tasks.cancel(id, body.expectedVersion())
							.switchIfEmpty(Mono.error(new MarketplaceException(409, "任务已变更，请刷新后重试")))
							// 任务书 #90 C90-02：pending/reconsent 报名同事务终态化 cancelled
							// （V53 trigger 置 cancelled_at），逐条发 ApplicationCancelled 供推荐官通知。
							.flatMap(task -> apps.cancelPendingByTask(task.id())
									.flatMap(cancelled -> outbox.append(ApplicationEvents
											.envelope("ApplicationCancelled", cancelled, task.ownerAccountId()))
											.thenReturn(cancelled))
									.collectList()
									.flatMap(cancelledList -> taskPromotionService.unlinkPromotionBackfill(task)
											.then(outbox.append(TaskEvents.taskCancelledEnvelope(task)))
											.thenReturn(new CancelSweep(task, cancelledList.size())))))
					.flatMap(sweep -> finalizeCancellation(sweep.task(), sweep.pendingCancelled())
							.map(counts -> result(sweep.task(), counts)));
		});
	}

	private Mono<CancelCounts> finalizeCancellation(Task task, int pendingCancelled) {
		return resolveCancellationForAccepted(task).flatMap(counts -> apps.countReservingByTask(task.id())
				.map(compensationPending -> new CancelCounts(pendingCancelled, counts.refundedCount(),
						compensationPending, counts.settledWithCompensation())));
	}

	private Mono<CancellationCounts> resolveCancellationForAccepted(Task task) {
		return apps.findAcceptedNeedingCancelResolution(task.id())
				.concatMap(app -> milestoneService.hasConfirmedMilestone(app.id()).flatMap(hasConfirmed -> (hasConfirmed
						? settleCancelledEngagement(task, app).thenReturn(1)
						: refundOnCancel(task, app).then(transactions.transactional(apps
								.markRefunded(app.id(), task.id())
								.flatMap(refunded -> outbox
										.append(TaskEvents.engagementRefundedEnvelope(task, refunded)).thenReturn(1))))
								.then(Mono.just(0)))
						// 任务书 #97 D97-05：商家取消终态先到 → 残留协商退出申请自动 cancelled（计数保留）。
						.flatMap(code -> exits.cancelPendingByApplication(app.id()).thenReturn(code))))
				.collectList()
				.map(codes -> new CancellationCounts((int) codes.stream().filter(code -> code == 0).count(),
						(int) codes.stream().filter(code -> code == 1).count()));
	}

	private Mono<Void> settleCancelledEngagement(Task task, TaskApplication app) {
		return milestoneService.computeSettlement(app, task).flatMap(breakdown -> {
			Mono<Void> bountyLeg;
			if (app.bountyCents() > 0 && !breakdown.isEmpty()) {
				bountyLeg = finance
						.captureVerified(task.organizationId(), app.id(), app.bountyCents(), app.recommenderAccountId(),
								breakdown.totalCents())
						.flatMap(outcome -> outcome.captured()
								? finance.release(task.organizationId(), app.id())
								: Mono.error(new com.grassland.marketplace.workflow.FinanceEscrowException(
										"cancel settlement capture needs reconciliation: "
												+ outcome.reconciliationReason())));
			} else if (app.bountyCents() > 0) {
				bountyLeg = finance.release(task.organizationId(), app.id());
			} else {
				bountyLeg = Mono.empty();
			}
			Mono<Void> freebieLeg = app.freebieDepositCents() > 0
					? finance.freebieRefund(task.organizationId(), app.id())
					: Mono.empty();
			// 注意 Mono<Void> 空信号语义：终态化成功/重试幂等两态用哨兵布尔区分，
			// 重试（0 行）只补里程碑金额回填，不重发事件（确定性 eventId 本身也幂等）。
			Mono<Boolean> flip = apps.markCancelledWithSettlement(app.id(), task.id())
					.flatMap(cancelled -> milestoneService.recordSettlementAmounts(app, breakdown)
							.then(outbox.append(TaskEvents.cancelledWithSettlementEnvelope(task, cancelled, breakdown)))
							.thenReturn(true))
					.defaultIfEmpty(false);
			// 资金调用在本地事务外；终态、结算金额与 outbox 必须原子提交。
			// 任一数据库写入失败仍保留 accepted，使下一次取消请求能够恢复。
			return freebieLeg.then(bountyLeg)
					.then(transactions.transactional(flip.flatMap(flipped -> flipped
							? Mono.<Void>empty()
							: milestoneService.recordSettlementAmounts(app, breakdown))));
		});
	}

	private Mono<Void> refundOnCancel(Task task, TaskApplication app) {
		Mono<Void> freebieLeg = app.freebieDepositCents() > 0
				? finance.freebieRefund(task.organizationId(), app.id())
				: Mono.empty();
		Mono<Void> bountyLeg = app.bountyCents() > 0 ? finance.release(task.organizationId(), app.id()) : Mono.empty();
		return freebieLeg.then(bountyLeg);
	}
	private record CancelSweep(Task task, int pendingCancelled) {
	}
	private record CancelCounts(int pendingCancelled, int refundedCount, int compensationPending,
			int settledWithCompensation) {
	}
	private record CancellationCounts(int refundedCount, int settledWithCompensation) {
	}

	public record Result(Task task, int pendingCancelled, int refundedCount, int compensationPending,
			int settledWithCompensation) {
	}
	private Result result(Task task, CancelCounts counts) {
		return new Result(task, counts.pendingCancelled(), counts.refundedCount(), counts.compensationPending(),
				counts.settledWithCompensation());
	}

}
