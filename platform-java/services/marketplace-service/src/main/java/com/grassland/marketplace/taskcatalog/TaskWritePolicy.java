package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Component;

/**
 * Task write authorization and validation; does not own transitions or HTTP
 * binding.
 */
@Component
public class TaskWritePolicy {
	private final TaskPublishGate publishGate;
	private final TaskResourceAuthorization taskAuthorization;

	public TaskWritePolicy(TaskPublishGate publishGate, TaskResourceAuthorization taskAuthorization) {
		this.publishGate = publishGate;
		this.taskAuthorization = taskAuthorization;
	}

	Mono<Void> enforceReviseFundingContract(Task current, ReviseTaskRequest body) {
		try {
			TaskCatalogFundingRules.validate(effectiveRequirements(current, body), body.freebieDepositCents(),
					body.bountyCents(), body.commercePackageId());
		} catch (IllegalArgumentException error) {
			return Mono.error(new MarketplaceException(400, error.getMessage()));
		}
		return Mono.empty();
	}

	static TaskRequirements effectiveRequirements(Task current, ReviseTaskRequest body) {
		return body.requirements() == null ? current.requirements() : body.requirements();
	}

	Mono<Void> enforceQuestionPlatform(String platform, TaskQuestion question) {
		if (!TaskQuestion.orNone(question).present()) {
			return Mono.empty();
		}
		if (!"zhihu".equalsIgnoreCase(platform == null ? "" : platform.trim())) {
			return Mono.error(new MarketplaceException(422, "目标问题仅支持知乎平台任务"));
		}
		return Mono.empty();
	}

	Mono<Void> enforceRequiredTaskFields(Task task) {
		return Mono.fromRunnable(
				() -> TaskFieldPolicy.validateRequired(task.platform(), task.storeId(), task.applicationDeadline()));
	}

	Mono<Void> enforceBountyTierGate(String permissionTier, Long bountyCents, Long freebieDepositCents) {
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

	Mono<Void> enforceLadderBudget(TaskRequirements requirements, Long bountyCents) {
		if (requirements != null && requirements.commissionLadder() != null) {
			try {
				requirements.commissionLadder().validateReserve(bountyCents);
			} catch (IllegalArgumentException error) {
				return Mono.error(new MarketplaceException(400, error.getMessage()));
			}
		}
		return Mono.empty();
	}

	Mono<Void> enforceFundingSingleMode(Task current, TaskRequirements requirements, Long bountyCents,
			Long freebieDepositCents, String commercePackageId) {
		try {
			TaskCatalogFundingRules.validate(requirements == null ? current.requirements() : requirements,
					freebieDepositCents == null ? current.freebieDepositCents() : freebieDepositCents,
					bountyCents == null ? current.bountyCents() : bountyCents,
					commercePackageId == null ? current.commercePackageId() : commercePackageId);
		} catch (IllegalArgumentException error) {
			return Mono.error(new MarketplaceException(400, error.getMessage()));
		}
		return Mono.empty();
	}

	Mono<Void> enforceInteractionBinding(String contentForm, TaskRequirements mergedRequirements) {
		try {
			TaskRequirements.validateInteractionBinding(contentForm, mergedRequirements);
		} catch (IllegalArgumentException error) {
			return Mono.error(new MarketplaceException(400, error.getMessage()));
		}
		return Mono.empty();
	}

	Mono<Void> enforcePublishGates(String organizationId, String permissionTier, Long bountyCents,
			Long freebieDepositCents) {
		return publishGate.enforce(organizationId, permissionTier, bountyCents, freebieDepositCents);
	}

	Mono<Task> loadManageableTask(String taskId, Caller caller, String requiredStatus) {
		return loadManageableTaskAccess(taskId, caller, requiredStatus)
				.map(TaskResourceAuthorization.ManagedTask::task);
	}

	Mono<TaskResourceAuthorization.ManagedTask> loadManageableTaskAccess(String taskId, Caller caller,
			String requiredStatus) {
		return taskAuthorization.requireManager(taskId, caller)
				.filter(access -> requiredStatus == null || requiredStatus.equals(access.task().status()))
				.switchIfEmpty(TaskBodies.fail(409, "任务当前状态不允许该操作"));
	}

}
