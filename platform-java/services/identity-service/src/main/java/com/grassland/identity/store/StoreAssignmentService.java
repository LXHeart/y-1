package com.grassland.identity.store;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.membership.MembershipRepository;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 门店分配服务（任务书 #52）：主体池内成员 ↔ 门店 的挂靠调配。
 *
 * <p>与 #49 下线的外部挂靠（任意 accountId 挂入）不同：这里只操作<b>本主体内</b>的账号
 * （有组织关系即可；2026-08-28 拍板 owner/admin 也可挂店——管理层亲自运营某店时领名分，
 * 权限本就由 orgSuperUserAsManager 覆盖，分配只是如实呈现），门禁在 controller 层收归主体 ADMIN+（决策 C）。
 *
 * <ul>
 * <li>{@link #assign} = 分配或调度（assign-or-move）：已挂他店则同事务先解除再挂目标店
 * （决策 D：成员至多挂一店）；同店重复调用 = 改角色。role=manager 过一店一店长闸（决策 B，
 * 排除自身故同店 staff→manager 改角色不被自己挡住）。</li>
 * <li>{@link #remove} = 移除回池：只解除挂靠，账号保留为池内成员；删号走主体区既有端点。</li>
 * </ul>
 *
 * <p>两动作均与 outbox 审计事件同事务（StoreMembershipAssigned / Removed）。
 */
@Service
public class StoreAssignmentService {

    private final StoreMembershipRepository storeMemberships;
    private final MembershipRepository orgMemberships;
    private final StoreRepository stores;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;

    public StoreAssignmentService(StoreMembershipRepository storeMemberships,
            MembershipRepository orgMemberships, StoreRepository stores, OutboxRepository outbox,
            TransactionalOperator transactions) {
        this.storeMemberships = storeMemberships;
        this.orgMemberships = orgMemberships;
        this.stores = stores;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    public Mono<StoreMembership> assign(String operatorAccountId, String organizationId, String storeId,
            String accountId, String role) {
        if (!"manager".equals(role) && !"staff".equals(role)) {
            return Mono.error(new IdentityException(400, "role 仅支持 manager/staff"));
        }
        return ensureStoreInOrg(organizationId, storeId)
                .then(ensurePoolMember(accountId, organizationId))
                .then(guardUniqueManagerIfNeeded(storeId, accountId, role))
                // 删旧挂靠与建新挂靠同事务：中途失败不能留下「两边都挂/两边都没挂」
                .then(transactions.transactional(
                        storeMemberships.deleteAllByAccountInOrg(accountId, organizationId).then(
                                storeMemberships.create(storeId, accountId, role))
                                .flatMap(m -> appendAssignedEvent(operatorAccountId, organizationId, m)
                                        .thenReturn(m))));
    }

    public Mono<Void> remove(String operatorAccountId, String organizationId, String storeId,
            String accountId) {
        return ensureStoreInOrg(organizationId, storeId)
                .then(storeMemberships.findRole(storeId, accountId)
                        .switchIfEmpty(Mono.error(new IdentityException(404, "该成员不在此门店")))
                        .then())
                .then(transactions.transactional(
                        storeMemberships.deleteByStoreAndAccount(storeId, accountId).then(
                                appendRemovedEvent(operatorAccountId, organizationId, storeId, accountId))));
    }

    private Mono<Void> ensureStoreInOrg(String organizationId, String storeId) {
        return stores.findByOrganizationAndId(organizationId, storeId)
                .switchIfEmpty(Mono.error(new IdentityException(404, "门店不存在")))
                .then();
    }

    /**
     * 分配对象须为本 org 成员（owner/admin/member 皆可——2026-08-28 拍板放开管理层；
     * 非本 org 账号 404 跨主体隔离）。挂店为店长即占位（一店一店长闸照常生效）。
     */
    private Mono<Void> ensurePoolMember(String accountId, String organizationId) {
        return orgMemberships.findRole(organizationId, accountId)
                .switchIfEmpty(Mono.error(new IdentityException(404, "该账号不是本主体成员")))
                .then();
    }

    /** 一店一店长（决策 B）：排除自身（同店改角色）后已有店长行 → 409。 */
    private Mono<Void> guardUniqueManagerIfNeeded(String storeId, String accountId, String role) {
        if (!"manager".equals(role)) {
            return Mono.empty();
        }
        return storeMemberships.countManagerRows(storeId, accountId)
                .flatMap(count -> count > 0
                        ? Mono.error(new IdentityException(409, "该门店已有店长，请先移除或调度原店长"))
                        : Mono.<Void>empty());
    }

    private Mono<Void> appendAssignedEvent(String operatorAccountId, String organizationId,
            StoreMembership membership) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", organizationId);
        payload.put("storeId", membership.storeId());
        payload.put("accountId", membership.accountId());
        payload.put("role", membership.role());
        payload.put("operatorAccountId", operatorAccountId);
        return outbox.append(new EventEnvelope(UUID.randomUUID().toString(), "StoreMembershipAssigned",
                "StoreMembership", membership.id(), 1, Instant.now(), null, payload));
    }

    private Mono<Void> appendRemovedEvent(String operatorAccountId, String organizationId, String storeId,
            String accountId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", organizationId);
        payload.put("storeId", storeId);
        payload.put("accountId", accountId);
        payload.put("operatorAccountId", operatorAccountId);
        return outbox.append(new EventEnvelope(UUID.randomUUID().toString(), "StoreMembershipRemoved",
                "StoreMembership", accountId, 1, Instant.now(), null, payload));
    }
}
