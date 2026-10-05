package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.reputation.ReputationService;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Component;

/** Read-side participant visibility and effective recommender level. */
@Component
public class TaskReadAccess {
	private final TaskApplicationRepository apps;
	private final ReputationService reputationService;

	public TaskReadAccess(TaskApplicationRepository apps, ReputationService reputationService) {
		this.apps = apps;
		this.reputationService = reputationService;
	}

	Mono<Boolean> appliedBy(Caller caller, String taskId) {
		if (caller.accountId() == null) {
			return Mono.just(false);
		}
		return apps.findByTaskAndRecommender(taskId, caller.accountId()).hasElement();
	}

	Mono<Integer> visibleRecommenderLevel(Caller caller) {
		return reputationService.snapshot(caller.accountId())
				.map(snapshot -> snapshot.evaluation().effectiveLevel().number());
	}

}
