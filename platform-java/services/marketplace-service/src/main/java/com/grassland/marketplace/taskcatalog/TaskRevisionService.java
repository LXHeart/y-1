package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Component;

/**
 * Revision transaction: version, renewed consent, review and outbox advance
 * together.
 */
@Component
public class TaskRevisionService {
	private final TaskRepository tasks;
	private final OutboxRepository outbox;
	private final TaskApplicationRepository apps;
	private final TransactionalOperator transactions;
	private final TaskFullAutoCloser taskFullAutoCloser;
	private final TaskWritePolicy taskWritePolicy;
	private final TaskPromotionService taskPromotionService;

	public TaskRevisionService(TaskRepository tasks, OutboxRepository outbox, TaskApplicationRepository apps,
			TransactionalOperator transactions, TaskFullAutoCloser taskFullAutoCloser, TaskWritePolicy taskWritePolicy,
			TaskPromotionService taskPromotionService) {
		this.tasks = tasks;
		this.outbox = outbox;
		this.apps = apps;
		this.transactions = transactions;
		this.taskFullAutoCloser = taskFullAutoCloser;
		this.taskWritePolicy = taskWritePolicy;
		this.taskPromotionService = taskPromotionService;
	}

	public Mono<Task> revise(String id, ReviseTaskRequest body, Caller caller) {
		return taskWritePolicy.loadManageableTaskAccess(id, caller, "published")
				.flatMap(access -> guardReviseApplications(id)
						.then(taskWritePolicy.enforceBountyTierGate(access.permissionTier(), body.bountyCents(),
								body.freebieDepositCents()))
						// 任务书 #90 C90-05 D90-10：修订是全量更新——互斥校验按「将要写入的值」
						// （显式 null = 清空）判定，不再回填当前值；草稿编辑仍走合并语义。
						.then(taskWritePolicy.enforceReviseFundingContract(access.task(), body))
						.then(taskPromotionService.enforceCommercePackageLinkable(access.task().organizationId(),
								body.commercePackageId(), id))
						.then(taskWritePolicy.enforceInteractionBinding(body.contentForm(),
								taskWritePolicy.effectiveRequirements(access.task(), body)))
						.then(taskWritePolicy.enforceLadderBudget(
								taskWritePolicy.effectiveRequirements(access.task(), body), body.bountyCents()))
						.then(taskWritePolicy.enforceQuestionPlatform(body.platform(), body.question()))
						.thenReturn(access.task())
						.flatMap(v -> transactions.transactional(tasks.revisePublished(id, body.expectedVersion(),
								body.title(), body.description(), body.contentForm(), body.platform(), body.maxSlots(),
								body.bountyCents(), body.applicationDeadline(), body.minRecommenderLevel(),
								body.requirements(), caller.accountId(), body.autoAcceptMinLevel(),
								body.freebieDepositCents(), body.question(), body.commercePackageId(),
								body.reviewRequired(), body.deliveryDeadlineDays(),
								body.cancelPolicy() == null ? null : ApplicationBodies.toJson(body.cancelPolicy()))
								.switchIfEmpty(Mono.error(new MarketplaceException(409, "任务已变更，请刷新后重试")))
								.flatMap(task -> taskPromotionService.relinkPromotionBackfill(access.task(), task)
										.then(reconsentSweepIfNeeded(access.task(), task))
										.then(outbox.append(TaskEvents.taskRevisedEnvelope(task)).thenReturn(task)))
								// 任务书 #90 C90-05 D90-06：关键条款（赏金/押金/平台/交付形态/交付要求）
								// 变化 → 同事务重进 pending_review——修订版在审核通过前不生效为公开版本，
								// 旧 task_version 快照不可变（不被覆盖）；展示字段修订保持 published。
								.flatMap(revised -> TaskContractTerms.changed(access.task(), revised)
										? resubmitRevisedForReview(revised)
										: Mono.just(revised))
								// #26 D13：修订提交成功的同事务末尾判定满员收口——下调 maxSlots
								// 至已接受数之下时任务即转 closed（同事务发 TaskClosed/slots_full）；
								// 未满/无上限 → empty，回落修订后的任务体（响应返回最终状态与版本）
								.flatMap(revised -> taskFullAutoCloser.closeIfFull(revised.id())
										.defaultIfEmpty(revised)))));
	}

	private Mono<Task> resubmitRevisedForReview(Task revised) {
		return tasks.resubmitForReview(revised.id(), revised.version())
				.switchIfEmpty(Mono.error(new MarketplaceException(409, "任务已变更，请刷新后重试")))
				.flatMap(under -> outbox.append(TaskEvents.taskResubmittedEnvelope(under)).thenReturn(under));
	}

	private Mono<Void> guardReviseApplications(String taskId) {
		return apps.countAcceptedOrReservingByTask(taskId)
				.flatMap(count -> count > 0
						? Mono.error(new MarketplaceException(409, "已有 " + count + " 名推荐官报名成功，任务不可再修改"))
						: Mono.empty());
	}

	private Mono<Void> reconsentSweepIfNeeded(Task before, Task after) {
		if (!TaskContractTerms.changed(before, after)) {
			return Mono.empty();
		}
		return apps.markReconsentRequiredByTask(after.id())
				.flatMap(app -> outbox.append(
						ApplicationEvents.envelope("ApplicationReconsentRequired", app, after.ownerAccountId())))
				.then();
	}

}
