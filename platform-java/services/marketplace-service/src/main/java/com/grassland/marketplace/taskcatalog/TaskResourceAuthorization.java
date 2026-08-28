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
            // HLD 7.4：资源级授权须服务端自查，不能只信断言——org 级改走 Identity 成员表判定
            //（ADMIN+，不传 storeId）。断言 organization_id 缺失/滞后的账号（先开通后建主体的
            // 历史序列）不再被整体 403；非 owner/admin 的 403 语义不变。
            if (!caller.isMerchant() || caller.accountId() == null) {
                return Mono.error(new MarketplaceException(403, "无权管理该组织资源"));
            }
            // minimumRole 在 identity org 级分支被忽略（固定要求 ADMIN+）；传 manager 仅凑非空契约。
            return stores.authorize(caller.accountId(), organizationId, null, "manager")
                    .map(decision -> new ScopeAccess(
                            decision.organizationId(), null, decision.permissionTier(), "organization"))
                    .onErrorResume(MarketplaceException.class, error -> error.status() == 403
                            // identity 侧门店文案不适用 org 级资源，统一回本资源语义
                            ? Mono.error(new MarketplaceException(403, "无权管理该组织资源"))
                            : Mono.error(error));
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

    /** Revalidates the task owner's current Identity role and organization tier for reviewer-side publication. */
    public Mono<ManagedTask> requireCurrentOwnerManager(Task task) {
        return stores.authorize(task.ownerAccountId(), task.organizationId(), task.storeId(), "manager")
                .map(decision -> new ManagedTask(task, decision.permissionTier()));
    }

    public record ScopeAccess(
            String organizationId, String storeId, String permissionTier, String scope) {}

    public record ManagedTask(Task task, String permissionTier) {}
}
