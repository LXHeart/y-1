package com.grassland.finance.security;

import com.grassland.identity.assertion.IdentityAssertionSigner;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * finance 调用者解析（Epic 4 Slice 4D/4F / HLD 7.4、11.1）：**仅**信任 {@code X-Grassland-Identity} 断言，
 * 无 cookie 回退（finance 是纯下游，识人完全靠断言，不读 identity 库）。
 *
 * <p>两类断言（HLD 11.1「服务身份」）：
 * <ul>
 *   <li><b>用户断言</b>（edge-bff 签发，{@code callerKind=user/null}）— 终端商家/推荐官，识人靠 {@code accountId}。</li>
 *   <li><b>服务断言</b>（领域服务现签，{@code callerKind=service} + {@code principal}）— 如 marketplace Saga 调 finance
 *       预留/释放。带 {@code organizationId} 上下文供 org 级授权。</li>
 * </ul>
 *
 * <p>断言缺/失效 → 401。资源级授权（如确属某 org）仍须服务端用 {@code caller.organizationId} 自查（HLD 7.4 末句）。
 * {@link Caller#isMerchant()} 对服务断言恒为 false——服务断言不可凭 activeIdentityType 冒充商家用户（防冒充）。
 */
@Component
public class FinanceCallerResolver {

    /** 受信任的 Saga 编排服务 principal（marketplace AcceptApplicationReservationWorkflow）。 */
    public static final String MARKETPLACE_SERVICE = "marketplace";

    /** 受信任的争议处置服务 principal（trust ReleaseHoldAndApplyDecisionActivity，Slice 6C Phase D）。 */
    public static final String TRUST_SERVICE = "trust";

    private final IdentityAssertionSigner signer;
    private final String headerName;

    public FinanceCallerResolver(IdentityAssertionSigner signer,
                                 @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName) {
        this.signer = signer;
        this.headerName = headerName;
    }

    public Mono<Caller> resolve(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(headerName);
        if (header == null || header.isBlank()) {
            return Mono.error(new FinanceException(401, "未登录"));
        }
        return Mono.justOrEmpty(signer.verify(header, Instant.now()))
                .map(a -> new Caller(a.accountId(), a.activeIdentityType(), a.organizationId(),
                        a.permissionTier(), a.callerKind(), a.principal(), a.role()))
                .switchIfEmpty(Mono.error(new FinanceException(401, "未登录")));
    }

    /** 仅终端商家用户（服务断言被拒）。用于 sandbox 充值等人工动作。 */
    public Mono<Caller> requireMerchant(ServerHttpRequest request) {
        return resolve(request)
                .filter(Caller::isMerchant)
                .switchIfEmpty(Mono.error(new FinanceException(403, "需要商家身份")));
    }

    /**
     * 要求当前调用方持有任一指定后台角色（GL-P2-ADMIN-001，含 PLATFORM_ADMIN 超集语义）。
     * 为财务对账台（GL-P2-ADMIN-006）铺路；finance 只信 BFF 断言的 role claim；服务断言恒 false。
     */
    public Mono<Caller> requireRole(ServerHttpRequest request,
                                     com.grassland.identity.assertion.BackendRole... required) {
        return resolve(request)
                .filter(caller -> caller.hasBackendRole(required))
                .switchIfEmpty(Mono.error(new FinanceException(403, "权限不足")));
    }

    /**
     * 接受 (终端商家用户 + {@code org==orgId}) 或 (服务 {@code principal==servicePrincipal} + {@code org==orgId})。
     * 供 Saga 跨服务 reserve（HLD 11.1）。org 不符或非授权调用方 → 403。
     */
    public Mono<Caller> authorizeForOrg(ServerHttpRequest request, String orgId, String servicePrincipal) {
        return resolve(request)
                .filter(c -> orgId.equals(c.organizationId())
                        && (c.isMerchant() || c.isServicePrincipal(servicePrincipal)))
                .switchIfEmpty(Mono.error(new FinanceException(403, "无权操作该组织账户")));
    }

    /** 仅指定服务 principal 且组织匹配。高价值内部编排命令不得由终端用户直接调用。 */
    public Mono<Caller> requireServiceForOrg(
            ServerHttpRequest request, String orgId, String servicePrincipal) {
        return resolve(request)
                .filter(c -> orgId.equals(c.organizationId())
                        && c.isServicePrincipal(servicePrincipal))
                .switchIfEmpty(Mono.error(new FinanceException(403, "无权执行内部资金对账")));
    }

    /**
     * 接受终端商家用户或指定服务 principal（org 由调用方按已加载资源自查）。供 Saga 跨服务 release
     * （release 的 org 在加载 reservation 后才知，故 org 校验留在 controller）。
     */
    public Mono<Caller> resolveMerchantOrService(ServerHttpRequest request, String servicePrincipal) {
        return resolve(request)
                .filter(c -> c.isMerchant() || c.isServicePrincipal(servicePrincipal))
                .switchIfEmpty(Mono.error(new FinanceException(403, "无权操作该组织预留")));
    }

    /** 接受终端商家用户或<b>任一</b>指定服务 principal（release/capture 现接受 marketplace 与 trust，Slice 6C Phase D）。 */
    public Mono<Caller> resolveMerchantOrServices(ServerHttpRequest request, String... servicePrincipals) {
        return resolve(request)
                .filter(c -> c.isMerchant() || c.isServicePrincipalAny(servicePrincipals))
                .switchIfEmpty(Mono.error(new FinanceException(403, "无权操作该组织预留")));
    }

    /** 断言解析出的调用者。
     *  {@code organizationId}/{@code permissionTier} 为商家身份关联 org 及其 tier（非商家为 null），供 org 级资源授权自查。
     *  {@code callerKind}/{@code principal} 标识用户 vs 服务断言（HLD 11.1）。 */
    public record Caller(String accountId, String activeIdentityType, String organizationId,
                         String permissionTier, String callerKind, String principal, String role) {
        /** 终端商家用户。服务断言（callerKind=service）恒为 false——防止服务断言凭 activeIdentityType 冒充商家。 */
        public boolean isMerchant() {
            return !"service".equalsIgnoreCase(callerKind)
                    && "merchant".equalsIgnoreCase(activeIdentityType);
        }

        public boolean isService() {
            return "service".equalsIgnoreCase(callerKind);
        }

        /** 是否为指定服务 principal 的服务断言。 */
        public boolean isServicePrincipal(String expectedPrincipal) {
            return isService() && expectedPrincipal != null && expectedPrincipal.equalsIgnoreCase(principal);
        }

        /** 是否为任一指定服务 principal 的服务断言（Slice 6C Phase D：release/capture 接受多 principal）。 */
        public boolean isServicePrincipalAny(String... principals) {
            if (!isService() || principals == null) {
                return false;
            }
            for (String p : principals) {
                if (p != null && p.equalsIgnoreCase(principal)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 是否持有任一指定后台角色（GL-P2-ADMIN-001，含 PLATFORM_ADMIN 超集语义）。服务断言恒 false。
         */
        public boolean hasBackendRole(com.grassland.identity.assertion.BackendRole... required) {
            return !isService() && com.grassland.identity.assertion.BackendRoles.hasAny(role, required);
        }
    }
}
