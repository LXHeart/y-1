package com.grassland.identity.membership;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.organization.CurrentAccountResolver;
import com.grassland.identity.organization.OrganizationRepository;
import com.grassland.identity.user.AuthUser;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 组织级鉴权。草场身份域 Slice 2F（HLD 5.2 authorization：资源级权限决策的种子）。
 *
 * <p>角色来源优先成员表；若成员表无记录但账号是该 org 的 {@code owner_account_id}，兜底视为 OWNER
 * （即使 org 创建时 seed 成员行缺失也不会把 owner 锁死）。不足 minRole 或非成员 → 403；未登录 → 401（由 resolver 抛）。
 */
@Component
public class OrgAuthorization {

    private final CurrentAccountResolver accounts;
    private final MembershipRepository memberships;
    private final OrganizationRepository organizations;

    public OrgAuthorization(CurrentAccountResolver accounts, MembershipRepository memberships,
                            OrganizationRepository organizations) {
        this.accounts = accounts;
        this.memberships = memberships;
        this.organizations = organizations;
    }

    /** 要求当前账号在 org 内角色不低于 minRole，放行返回该账号；否则 403。 */
    public Mono<AuthUser> requireRole(ServerHttpRequest request, String organizationId, MembershipRole minRole) {
        return accounts.resolve(request)
                .flatMap(account -> roleOf(account.id(), organizationId)
                        .switchIfEmpty(Mono.error(new IdentityException(403, "无权访问该组织")))
                        .flatMap(role -> role.isAtLeast(minRole)
                                ? Mono.just(account)
                                : Mono.<AuthUser>error(new IdentityException(403, "权限不足"))));
    }

    /** 解析当前账号在 org 的角色（成员表优先，owner_account_id 兜底）；非成员返回空 Mono。 */
    public Mono<MembershipRole> resolveRole(ServerHttpRequest request, String organizationId) {
        return accounts.resolve(request)
                .flatMap(account -> roleOf(account.id(), organizationId));
    }

    /**
     * 解析指定账号在 org 的角色（成员表优先，owner_account_id 兜底）；非成员返回空 Mono。
     * 草场身份域 Slice 2J：供门店鉴权（{@code StoreAuthorization}）判断 org 超管，无需重复 owner-fallback 逻辑。
     */
    public Mono<MembershipRole> roleOfAccount(String accountId, String organizationId) {
        return roleOf(accountId, organizationId);
    }

    private Mono<MembershipRole> roleOf(String accountId, String organizationId) {
        return memberships.findRole(organizationId, accountId)
                .map(MembershipRole::fromDb)
                .switchIfEmpty(ownerFallback(organizationId, accountId));
    }

    private Mono<MembershipRole> ownerFallback(String organizationId, String accountId) {
        return organizations.findById(organizationId)
                .filter(org -> accountId.equals(org.ownerAccountId()))
                .map(org -> MembershipRole.OWNER);
    }
}
