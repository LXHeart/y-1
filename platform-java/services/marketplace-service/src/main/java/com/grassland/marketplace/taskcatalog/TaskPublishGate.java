package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.security.MarketplaceException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Shared publication gates for merchant submission, reviewer approval, and SLA auto-approval. */
@Component
public class TaskPublishGate {

    private final TaskRepository tasks;

    public TaskPublishGate(TaskRepository tasks) {
        this.tasks = tasks;
    }

    public Mono<Void> enforce(String organizationId, String permissionTier, Long bountyCents) {
        MerchantTier tier = MerchantTier.fromDb(permissionTier);
        int maxActive = PublishQuotaPolicy.maxActiveTasks(tier);
        if (maxActive == 0) {
            return Mono.error(new MarketplaceException(403, "当前等级不可发布任务"));
        }
        long bounty = bountyCents == null ? 0L : bountyCents;
        long maxTx = PublishQuotaPolicy.maxTxAmountCents(tier);
        if (bounty > 0 && maxTx == 0) {
            return Mono.error(new MarketplaceException(403, "当前等级不可发布资金型任务"));
        }
        if (bounty > maxTx) {
            return Mono.error(new MarketplaceException(409, "赏金超出本组织单笔上限"));
        }
        int maxMonthly = PublishQuotaPolicy.maxMonthlyTasks(tier);
        return tasks.countActiveByOrganization(organizationId)
                .flatMap(active -> active >= maxActive
                        ? Mono.<Integer>error(new MarketplaceException(409, "已达本组织发布上限"))
                        : tasks.countCreatedThisMonthByOrganization(organizationId))
                .flatMap(monthly -> monthly >= maxMonthly
                        ? Mono.<Void>error(new MarketplaceException(409, "已达本组织本月发布上限"))
                        : Mono.empty());
    }
}
