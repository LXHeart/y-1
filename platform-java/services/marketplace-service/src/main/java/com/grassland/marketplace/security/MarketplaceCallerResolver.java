package com.grassland.marketplace.security;

import com.grassland.identity.assertion.IdentityAssertionSigner;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * marketplace 调用者解析（Epic 4 Slice 4A / HLD 7.4）：**仅**信任 edge-bff 签发的 {@code X-Grassland-Identity} 断言，
 * 无 cookie 回退（marketplace 是纯下游，识人完全靠 BFF 断言，不读 identity 库）。
 *
 * <p>断言缺/失效 → 401；{@link #requireMerchant} 额外要求 activeIdentityType=merchant（断言携带，identity_session 数据）→ 403。
 * 资源级授权（如 merchant 确属某 org）仍须服务端自查，不能只信断言（HLD 7.4 末句）。
 */
@Component
public class MarketplaceCallerResolver {

    private final IdentityAssertionSigner signer;
    private final String headerName;

    public MarketplaceCallerResolver(IdentityAssertionSigner signer,
                                     @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName) {
        this.signer = signer;
        this.headerName = headerName;
    }

    public Mono<Caller> resolve(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(headerName);
        if (header == null || header.isBlank()) {
            return Mono.error(new MarketplaceException(401, "未登录"));
        }
        return Mono.justOrEmpty(signer.verify(header, Instant.now()))
                .map(a -> new Caller(a.accountId(), a.activeIdentityType(), a.sessionToken(),
                        a.organizationId(), a.permissionTier(), a.callerKind(), a.principal(), a.role()))
                .switchIfEmpty(Mono.error(new MarketplaceException(401, "未登录")));
    }

    /**
     * 仅接受指定服务 principal（HLD 11.1 服务身份）。终端用户断言恒拒绝——
     * 内部端点（如争议参与方授权）只允许受信任服务（trust）以现签服务断言调用。
     */
    public Mono<Caller> requireServicePrincipal(ServerHttpRequest request, String servicePrincipal) {
        return resolve(request)
                .filter(c -> c.isServicePrincipal(servicePrincipal))
                .switchIfEmpty(Mono.error(new MarketplaceException(403, "无权调用内部端点")));
    }

    public Mono<Caller> requireMerchant(ServerHttpRequest request) {
        return resolve(request)
                .filter(Caller::isMerchant)
                .switchIfEmpty(Mono.error(new MarketplaceException(403, "需要商家身份")));
    }

    /** 推荐官报名等动作要求 activeIdentityType=recommender，否则 403。草场 Epic 4 Slice 4B。 */
    public Mono<Caller> requireRecommender(ServerHttpRequest request) {
        return resolve(request)
                .filter(Caller::isRecommender)
                .switchIfEmpty(Mono.error(new MarketplaceException(403, "需要推荐官身份")));
    }

    /**
     * 运营处置台操作者（GL-P1-OPS-001）：按<b>平台角色</b>判定（{@code app_users.role}），非业务身份。
     *
     * <p>与 trust 的 {@code requireCustomerService} 同口径 —— 运营处置是账号在平台侧的职能，
     * 与「当前 session 选了哪个业务视角」正交。历史上把这类职能建模成 {@code activeIdentityType}
     * 导致端点在真实链路恒 403（identity 的 IdentityType 只有 merchant/recommender），不重犯。
     */
    public Mono<Caller> requireOpsOperator(ServerHttpRequest request) {
        return resolve(request)
                .filter(Caller::isOpsOperator)
                .switchIfEmpty(Mono.error(new MarketplaceException(403, "需要运营或管理员角色")));
    }

    /** 断言解析出的调用者。{@code activeIdentityType} 为 null=消费者；merchant 发布任务 / recommender 报名。
     *  {@code organizationId}/{@code permissionTier} 为商家身份关联 org 及其 tier（非商家为 null），供 org 级授权/限额（4B+）。
     *  {@code role} 是平台角色（{@code app_users.role}：user/admin/customer_service），供运营处置台闸门。 */
    public record Caller(String accountId, String activeIdentityType, String sessionToken,
                         String organizationId, String permissionTier, String callerKind, String principal,
                         String role) {
        public boolean isMerchant() {
            return !"service".equalsIgnoreCase(callerKind)
                    && "merchant".equalsIgnoreCase(activeIdentityType);
        }

        public boolean isRecommender() {
            return !"service".equalsIgnoreCase(callerKind)
                    && "recommender".equalsIgnoreCase(activeIdentityType);
        }

        /**
         * 运营处置台操作者（{@code customer_service} 或 {@code admin}，后者为超集）。
         * 服务断言不可冒充 —— {@code callerKind=service} 恒 false（服务没有人类 role）。
         */
        public boolean isOpsOperator() {
            if ("service".equalsIgnoreCase(callerKind)) {
                return false;
            }
            return "customer_service".equalsIgnoreCase(role) || "admin".equalsIgnoreCase(role);
        }

        public boolean isService() {
            return "service".equalsIgnoreCase(callerKind);
        }

        public boolean isServicePrincipal(String expectedPrincipal) {
            return isService() && expectedPrincipal != null && expectedPrincipal.equalsIgnoreCase(principal);
        }
    }
}
