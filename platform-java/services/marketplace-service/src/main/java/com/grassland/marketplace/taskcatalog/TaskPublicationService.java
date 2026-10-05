package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Component;

/** Creation submitted for review, draft publication and recruitment closure. */
@Component
public class TaskPublicationService {
	private final TaskResourceAuthorization taskAuthorization;
	private final TaskReviewService taskReviewService;
	private final TaskRepository tasks;
	private final OutboxRepository outbox;
	private final TransactionalOperator transactions;
	private final TaskWritePolicy taskWritePolicy;
	private final TaskPromotionService taskPromotionService;

	public TaskPublicationService(TaskReviewService taskReviewService, TaskResourceAuthorization taskAuthorization,
			TaskRepository tasks, OutboxRepository outbox, TransactionalOperator transactions,
			TaskWritePolicy taskWritePolicy, TaskPromotionService taskPromotionService) {
		this.taskAuthorization = taskAuthorization;
		this.taskReviewService = taskReviewService;
		this.tasks = tasks;
		this.outbox = outbox;
		this.transactions = transactions;
		this.taskWritePolicy = taskWritePolicy;
		this.taskPromotionService = taskPromotionService;
	}

	public Mono<Task> create(CreateTaskRequest body, Caller caller) {
		return taskAuthorization
				.requireScope(caller, body.organizationId(), TaskBodies.blankToNull(body.storeId()), "manager")
				.flatMap(access -> transactions.transactional(tasks
						.acquireOrganizationPublishLock(access.organizationId())
						.then(taskWritePolicy.enforcePublishGates(access.organizationId(), access.permissionTier(),
								body.bountyCents(), body.freebieDepositCents()))
						.then(taskWritePolicy.enforceLadderBudget(body.requirements(), body.bountyCents()))
						.then(taskWritePolicy.enforceQuestionPlatform(body.platform(), body.question()))
						// 任务书 #75 卡 A：套餐推广第三分支——发布校验（存在/同主体/上架/未被占用）+ 创建成功回填。
						.then(taskPromotionService.enforceCommercePackageLinkable(access.organizationId(),
								body.commercePackageId(), null))
						.then(tasks.create(caller.accountId(), access.organizationId(), body.title(),
								body.description(), body.contentForm(), body.platform(), body.maxSlots(),
								body.bountyCents(), body.applicationDeadline(), body.minRecommenderLevel(),
								access.storeId(), body.requirements(), body.autoAcceptMinLevel(),
								body.freebieDepositCents(), body.question(), body.commercePackageId(),
								body.reviewRequired(), body.deliveryDeadlineDays(),
								body.cancelPolicy() == null ? null : ApplicationBodies.toJson(body.cancelPolicy())))
						.flatMap(task -> taskPromotionService.linkPromotionBackfill(task).thenReturn(task))
						.flatMap(taskReviewService::submit)));
	}

	public Mono<Task> publish(String id, TaskLifecycleRequest body, Caller caller) {
		return taskWritePolicy.loadManageableTaskAccess(id, caller, "draft")
				.flatMap(access -> transactions
						.transactional(tasks.acquireOrganizationPublishLock(access.task().organizationId())
								.then(taskWritePolicy.enforcePublishGates(access.task().organizationId(),
										access.permissionTier(), access.task().bountyCents(),
										access.task().freebieDepositCents()))
								.then(taskWritePolicy.enforceLadderBudget(access.task().requirements(),
										access.task().bountyCents()))
								// 任务书 #77 卡 B（D2）：publish 对存量草稿落库值同样校验三字段（防旧客户端绕过表单必填）。
								.then(taskWritePolicy.enforceRequiredTaskFields(access.task()))
								.then(tasks.publish(id, body.expectedVersion())
										.switchIfEmpty(Mono.error(new MarketplaceException(409, "任务已变更，请刷新后重试")))
										.flatMap(taskReviewService::submit))));
	}

	public Mono<Task> close(String id, TaskLifecycleRequest body, Caller caller) {
		return taskWritePolicy.loadManageableTask(id, caller, null).flatMap(task -> {
			if (TaskStatus.CLOSED.dbValue().equals(task.status())) {
				return Mono.just(task); // 幂等重试：返回当前任务体，不重复发事件
			}
			if (!TaskStatus.PUBLISHED.dbValue().equals(task.status())) {
				return Mono.<Task>error(new MarketplaceException(409, "任务当前状态不允许该操作"));
			}
			// 任务书 #90 C90-03 D90-03：招募关闭（closed）不终止套餐推广——不再清空 backfill，
			// 已接受推广继续按有效期归因；终止推广走 end-promotion / 下架 / 取消。
			return transactions.transactional(tasks.close(id, body.expectedVersion())
					.switchIfEmpty(Mono.error(new MarketplaceException(409, "任务已变更，请刷新后重试")))
					.flatMap(closed -> outbox.append(TaskEvents.taskClosedEnvelope(closed)).thenReturn(closed)));
		});
	}

}
