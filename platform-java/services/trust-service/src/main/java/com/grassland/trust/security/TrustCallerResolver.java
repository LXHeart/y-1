package com.grassland.trust.security;

import com.grassland.identity.assertion.IdentityAssertionSigner;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * trust 调用者解析（草场 Epic 6 Slice 6A / HLD 7.4、11.1）：**仅**信任 {@code X-Grassland-Identity} 断言，
 * 无 cookie 回退（trust 是纯下游）。复刻 finance 的 {@code FinanceCallerResolver}。
 *
 * <p>接受用户断言（merchant/recommender，OpenDispute/Decide）与 marketplace 服务断言（principal=marketplace，
 * 开放争议查询）。{@link Caller#isMerchant()} 对 service callerKind 恒 false（防服务断言冒充商家）。
 */
@Component
public class TrustCallerResolver {

    /** 受信任的 Saga 编排服务 principal（marketplace SettlementWindowWorkflow 查争议）。 */
    public static final String MARKETPLACE_SERVICE = "marketplace";

    private final IdentityAssertionSigner signer;
    private final String headerName;

    public TrustCallerResolver(IdentityAssertionSigner signer,
                               @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName) {
        this.signer = signer;
        this.headerName = headerName;
    }

    public Mono<Caller> resolve(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(headerName);
        if (header == null || header.isBlank()) {
            return Mono.error(new TrustException(401, "未登录"));
        }
        return Mono.justOrEmpty(signer.verify(header, Instant.now()))
                .map(a -> new Caller(a.accountId(), a.activeIdentityType(), a.organizationId(),
                        a.callerKind(), a.principal()))
                .switchIfEmpty(Mono.error(new TrustException(401, "未登录")));
    }

    /** 仅终端商家用户（Decide 用）。 */
    public Mono<Caller> requireMerchant(ServerHttpRequest request) {
        return resolve(request)
                .filter(Caller::isMerchant)
                .switchIfEmpty(Mono.error(new TrustException(403, "需要商家身份")));
    }

    /** 商家或推荐官（OpenDispute：HLD 10.5 Party = 商家/推荐官）。 */
    public Mono<Caller> requireMerchantOrRecommender(ServerHttpRequest request) {
        return resolve(request)
                .filter(c -> c.isMerchant() || c.isRecommender())
                .switchIfEmpty(Mono.error(new TrustException(403, "需要商家或推荐官身份")));
    }

    /** 接受终端商家或指定服务 principal（org 由调用方按已加载资源自查）。开放争议查询用（marketplace 调）。 */
    public Mono<Caller> resolveMerchantOrService(ServerHttpRequest request, String servicePrincipal) {
        return resolve(request)
                .filter(c -> c.isMerchant() || c.isServicePrincipal(servicePrincipal))
                .switchIfEmpty(Mono.error(new TrustException(403, "无权查询争议")));
    }

    /** 断言解析出的调用者。{@code callerKind}/{@code principal} 标识用户 vs 服务断言（HLD 11.1）。 */
    public record Caller(String accountId, String activeIdentityType, String organizationId,
                         String callerKind, String principal) {
        public boolean isMerchant() {
            return !"service".equalsIgnoreCase(callerKind)
                    && "merchant".equalsIgnoreCase(activeIdentityType);
        }

        public boolean isRecommender() {
            return !"service".equalsIgnoreCase(callerKind)
                    && "recommender".equalsIgnoreCase(activeIdentityType);
        }

        public boolean isService() {
            return "service".equalsIgnoreCase(callerKind);
        }

        public boolean isServicePrincipal(String expectedPrincipal) {
            return isService() && expectedPrincipal != null && expectedPrincipal.equalsIgnoreCase(principal);
        }
    }
}
