package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.security.IdentityStoreAuthorizationClient;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Centralizes legacy organization-owner and independent store-role authorization for tasks. */
@Component
public class TaskResourceAuthorization {

    private final TaskRepository tasks;
    private final IdentityStoreAuthorizationClient stores;

    public TaskResourceAuthorization(TaskRepository tasks, IdentityStoreAuthorizationClient stores) {
        this.tasks = tasks;
        this.stores = stores;
    }

    public Mono<ScopeAccess> requireScope(
            Caller caller, String organizationId, String storeId, String minimumRole) {
        if (storeId == null || storeId.isBlank()) {
            if (!caller.isMerchant() || !organizationId.equals(caller.organizationId())) {
                return Mono.error(new MarketplaceException(403, "无权管理该组织资源"));
            }
            return Mono.just(new ScopeAccess(organizationId, null, caller.permissionTier(), "organization"));
        }
        if (caller.isService() || caller.accountId() == null) {
            return Mono.error(new MarketplaceException(403, "需要用户身份"));
        }
        return stores.authorize(caller.accountId(), organizationId, storeId, minimumRole)
                .map(decision -> new ScopeAccess(
                        decision.organizationId(), decision.storeId(), decision.permissionTier(), decision.scope()));
    }

    public Mono<ManagedTask> requireManager(String taskId, Caller caller) {
        return tasks.findById(taskId)
                .switchIfEmpty(Mono.error(new MarketplaceException(404, "任务不存在")))
                .flatMap(task -> requireManager(task, caller));
    }

    public Mono<ManagedTask> requireManager(Task task, Caller caller) {
        if (task.storeId() == null) {
            boolean owner = caller.isMerchant()
                    && caller.accountId().equals(task.ownerAccountId())
                    && (caller.organizationId() == null
                            || task.organizationId().equals(caller.organizationId()));
            return owner
                    ? Mono.just(new ManagedTask(task, caller.permissionTier()))
                    : Mono.error(new MarketplaceException(403, "无权操作该任务"));
        }
        return requireScope(caller, task.organizationId(), task.storeId(), "manager")
                .map(access -> new ManagedTask(task, access.permissionTier()));
    }

    public Mono<Boolean> canManage(Task task, Caller caller) {
        return requireManager(task, caller).map(ignored -> true)
                .onErrorResume(MarketplaceException.class, error ->
                        error.status() == 403 || error.status() == 404
                                ? Mono.just(false)
                                : Mono.error(error));
    }

    public record ScopeAccess(
            String organizationId, String storeId, String permissionTier, String scope) {}

    public record ManagedTask(Task task, String permissionTier) {}
}
