package com.grassland.identity.store;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.membership.MembershipRole;
import com.grassland.identity.membership.OrgAuthorization;
import com.grassland.identity.organization.CurrentAccountResolver;
import com.grassland.identity.organization.OrganizationRepository;
import com.grassland.identity.user.AuthUser;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 门店粒度鉴权。草场身份域 Slice 2J（HLD 5.2 store-membership：门店范围成员和资源授权）。
 *
 * <p>角色来源：{@link StoreMembershipRepository#findRole} 命中用之；org {@code OWNER}/{@code ADMIN} 作为 org 级超管
 * <b>隐式视为门店 MANAGER</b>（满足任意门店角色要求）。不足 minRole / 非成员且非 org 超管 → 403；
 * store 不属于该 org → 404（跨 org 隔离，{@link #ensureStoreInOrg}）。
 *
 * <p>镜像 {@link OrgAuthorization} 的组织级鉴权风格。
 */
@Component
public class StoreAuthorization {

    private final CurrentAccountResolver accounts;
    private final StoreRepository stores;
    private final StoreMembershipRepository storeMemberships;
    private final OrgAuthorization orgAuthz;
    private final OrganizationRepository organizations;

    public StoreAuthorization(CurrentAccountResolver accounts, StoreRepository stores,
                              StoreMembershipRepository storeMemberships, OrgAuthorization orgAuthz,
                              OrganizationRepository organizations) {
        this.accounts = accounts;
        this.stores = stores;
        this.storeMemberships = storeMemberships;
        this.orgAuthz = orgAuthz;
        this.organizations = organizations;
    }

    /** 要求当前账号在该 store 角色不低于 min（store 命中或 org OWNER/ADMIN 隐式）；跨 org storeId → 404；不足 → 403。 */
    public Mono<AuthUser> requireStoreRole(ServerHttpRequest request, String orgId, String storeId, StoreRole min) {
        return accounts.resolve(request)
                .flatMap(account -> ensureStoreInOrg(orgId, storeId)
                        .then(roleOf(account.id(), orgId, storeId))
                        .switchIfEmpty(Mono.error(new IdentityException(403, "无权访问该门店")))
                        .flatMap(role -> role.isAtLeast(min)
                                ? Mono.just(account)
                                : Mono.<AuthUser>error(new IdentityException(403, "权限不足"))));
    }

    /** 解析当前账号在该 store 的角色（store_membership 优先；org OWNER/ADMIN 隐式 MANAGER）；非成员且非超管 → 空 Mono。 */
    public Mono<StoreRole> resolveStoreRole(ServerHttpRequest request, String orgId, String storeId) {
        return accounts.resolve(request)
                .flatMap(account -> ensureStoreInOrg(orgId, storeId).then(roleOf(account.id(), orgId, storeId)));
    }

    /**
     * Trusted domain services use this account-explicit variant for resource authorization.
     * A null storeId means organization-wide resource access and therefore requires org ADMIN/OWNER.
     */
    public Mono<Decision> authorizeAccount(
            String accountId, String orgId, String storeId, StoreRole minimumRole) {
        if (storeId == null) {
            return orgAuthz.roleOfAccount(accountId, orgId)
                    .filter(role -> role.isAtLeast(MembershipRole.ADMIN))
                    .flatMap(ignored -> decision(StoreRole.MANAGER, "organization", orgId))
                    .switchIfEmpty(Mono.error(new IdentityException(403, "无权管理该组织资源")));
        }
        return ensureStoreInOrg(orgId, storeId)
                .then(roleOf(accountId, orgId, storeId))
                .switchIfEmpty(Mono.error(new IdentityException(403, "无权访问该门店")))
                .flatMap(role -> role.isAtLeast(minimumRole)
                        ? decision(role, "store", orgId)
                        : Mono.error(new IdentityException(403, "权限不足")));
    }

    private Mono<Decision> decision(StoreRole role, String scope, String orgId) {
        return organizations.findById(orgId)
                .switchIfEmpty(Mono.error(new IdentityException(404, "组织不存在")))
                .map(org -> new Decision(role, scope, org.id(), org.permissionTier()));
    }

    /** 校验 store 属于该 org，否则 404（跨 org 隔离）。 */
    public Mono<Void> ensureStoreInOrg(String orgId, String storeId) {
        return stores.findByOrganizationAndId(orgId, storeId)
                .switchIfEmpty(Mono.error(new IdentityException(404, "门店不存在")))
                .then();
    }

    private Mono<StoreRole> roleOf(String accountId, String orgId, String storeId) {
        return storeMemberships.findRole(storeId, accountId)
                .map(StoreRole::fromDb)
                .switchIfEmpty(orgSuperUserAsManager(orgId, accountId));
    }

    /** org OWNER/ADMIN 视为门店 MANAGER（隐式超管）；否则空 Mono（非门店成员）。 */
    private Mono<StoreRole> orgSuperUserAsManager(String orgId, String accountId) {
        return orgAuthz.roleOfAccount(accountId, orgId)
                .filter(role -> role.isAtLeast(MembershipRole.ADMIN))
                .map(role -> StoreRole.MANAGER);
    }

    public record Decision(StoreRole role, String scope, String organizationId, String permissionTier) {}
}
