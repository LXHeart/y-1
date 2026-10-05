package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.commerce.CommerceRepository;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Component;

/** Promotion package association and explicit promotion termination. */
@Component
public class TaskPromotionService {
	private final TaskRepository tasks;
	private final OutboxRepository outbox;
	private final TransactionalOperator transactions;
	private final CommerceRepository commercePackages;
	private final TaskWritePolicy taskWritePolicy;

	public TaskPromotionService(TaskRepository tasks, OutboxRepository outbox, TransactionalOperator transactions,
			CommerceRepository commercePackages, TaskWritePolicy taskWritePolicy) {
		this.tasks = tasks;
		this.outbox = outbox;
		this.transactions = transactions;
		this.commercePackages = commercePackages;
		this.taskWritePolicy = taskWritePolicy;
	}

	Mono<Void> enforceCommercePackageLinkable(String organizationId, String commercePackageId, String excludeTaskId) {
		if (commercePackageId == null || commercePackageId.isBlank()) {
			return Mono.empty();
		}
		String packageId = commercePackageId.trim();
		return commercePackages.findDetail(packageId)
				.switchIfEmpty(Mono.error(new MarketplaceException(400, "套餐不存在，不能关联推广任务"))).flatMap(detail -> {
					if (!organizationId.equals(detail.offer().organizationId())) {
						return Mono.error(new MarketplaceException(400, "套餐不属于当前商家主体"));
					}
					if (!"published".equals(detail.offer().status())) {
						return Mono.error(new MarketplaceException(400, "套餐未上架，不能关联推广任务"));
					}
					return tasks.countActivePromotionsByPackage(packageId, excludeTaskId)
							.flatMap(count -> count > 0
									? Mono.error(new MarketplaceException(409, "该套餐已有进行中的推广任务"))
									: Mono.empty());
				});
	}

	Mono<Void> linkPromotionBackfill(Task created) {
		return created.commercePackageId() == null
				? Mono.empty()
				: commercePackages.linkPromotionTask(created.commercePackageId(), created.id());
	}

	Mono<Void> relinkPromotionBackfill(Task before, Task after) {
		Mono<Void> unlinkOld = before.commercePackageId() != null
				&& !before.commercePackageId().equals(after.commercePackageId())
						? commercePackages.unlinkPromotionTaskByTask(after.id())
						: Mono.empty();
		return unlinkOld.then(linkPromotionBackfill(after));
	}

	Mono<Void> unlinkPromotionBackfill(Task task) {
		return task.commercePackageId() == null ? Mono.empty() : commercePackages.unlinkPromotionTaskByTask(task.id());
	}

	public Mono<Task> endPromotion(String id, TaskLifecycleRequest body, Caller caller) {
		return taskWritePolicy.loadManageableTask(id, caller, null).flatMap(task -> {
			if (task.commercePackageId() == null) {
				return Mono.<Task>error(new MarketplaceException(409, "非套餐推广任务，无推广可结束"));
			}
			return tasks.promotionEnded(id).flatMap(ended -> ended
					? Mono.just(task) // 幂等重试：返回当前任务体，不重复发事件
					: endPromotionNow(task, body.expectedVersion()));
		});
	}

	Mono<Task> endPromotionNow(Task task, int expectedVersion) {
		return transactions.transactional(tasks.endPromotion(task.id(), expectedVersion)
				.switchIfEmpty(Mono.error(new MarketplaceException(409, "任务已变更或推广不可结束，请刷新后重试")))
				.flatMap(ended -> unlinkPromotionBackfill(ended)
						.then(outbox.append(TaskEvents.taskPromotionEndedEnvelope(ended)).thenReturn(ended))));
	}

}
