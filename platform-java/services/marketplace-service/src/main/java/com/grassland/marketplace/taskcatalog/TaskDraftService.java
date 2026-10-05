package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Component;

/**
 * Draft creation/editing; validation, persistence and draft events share their
 * existing transaction.
 */
@Component
public class TaskDraftService {
	private final TaskResourceAuthorization taskAuthorization;
	private final TaskRepository tasks;
	private final OutboxRepository outbox;
	private final TransactionalOperator transactions;
	private final TaskWritePolicy taskWritePolicy;
	private final TaskPromotionService taskPromotionService;

	public TaskDraftService(TaskResourceAuthorization taskAuthorization, TaskRepository tasks, OutboxRepository outbox,
			TransactionalOperator transactions, TaskWritePolicy taskWritePolicy,
			TaskPromotionService taskPromotionService) {
		this.taskAuthorization = taskAuthorization;
		this.tasks = tasks;
		this.outbox = outbox;
		this.transactions = transactions;
		this.taskWritePolicy = taskWritePolicy;
		this.taskPromotionService = taskPromotionService;
	}

	public Mono<Task> createDraft(CreateDraftRequest body, Caller caller) {
		return taskAuthorization
				.requireScope(caller, body.organizationId(), TaskBodies.blankToNull(body.storeId()), "manager")
				.flatMap(access -> transactions.transactional(taskWritePolicy
						.enforceQuestionPlatform(body.platform(), body.question())
						.then(taskPromotionService.enforceCommercePackageLinkable(access.organizationId(),
								body.commercePackageId(), null))
						.then(tasks.createDraft(caller.accountId(), access.organizationId(), body.title(),
								body.description(), body.contentForm(), body.platform(), body.maxSlots(),
								body.bountyCents(), body.applicationDeadline(), body.minRecommenderLevel(),
								access.storeId(), body.requirements(), body.autoAcceptMinLevel(),
								body.freebieDepositCents(), body.question(), body.commercePackageId(),
								body.reviewRequired(), body.deliveryDeadlineDays(),
								body.cancelPolicy() == null ? null : ApplicationBodies.toJson(body.cancelPolicy())))
						.flatMap(task -> taskPromotionService.linkPromotionBackfill(task).thenReturn(task))));
	}

	public Mono<Task> update(String id, UpdateTaskRequest body, Caller caller) {
		return taskWritePolicy.loadManageableTask(id, caller, "draft")
				.flatMap(current -> transactions.transactional(taskWritePolicy
						.enforceFundingSingleMode(current, body.requirements(), body.bountyCents(),
								body.freebieDepositCents(), body.commercePackageId())
						.then(taskPromotionService.enforceCommercePackageLinkable(current.organizationId(),
								body.commercePackageId(), id))
						.then(taskWritePolicy.enforceInteractionBinding(body.contentForm(),
								body.requirements() == null ? current.requirements() : body.requirements()))
						.then(taskWritePolicy.enforceLadderBudget(
								body.requirements() == null ? current.requirements() : body.requirements(),
								body.bountyCents()))
						.then(taskWritePolicy.enforceQuestionPlatform(body.platform(), body.question()))
						.then(tasks.updateDraft(id, body.expectedVersion(), body.title(), body.description(),
								body.contentForm(), body.platform(), body.maxSlots(), body.bountyCents(),
								body.applicationDeadline(), body.minRecommenderLevel(), body.requirements(),
								body.autoAcceptMinLevel(), body.freebieDepositCents(), body.question(),
								body.commercePackageId(), body.reviewRequired(), body.deliveryDeadlineDays(),
								body.cancelPolicy() == null ? null : ApplicationBodies.toJson(body.cancelPolicy()))
								.switchIfEmpty(Mono.error(new MarketplaceException(409, "任务已变更，请刷新后重试")))
								.flatMap(task -> taskPromotionService.relinkPromotionBackfill(current, task).then(
										outbox.append(TaskEvents.taskDraftUpdatedEnvelope(task)).thenReturn(task))))));
	}

}
