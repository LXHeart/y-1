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
 * <p>接受用户断言（merchant/recommender，OpenDispute/Decide；judge 投票；customer_service 客服终审）与 marketplace 服务断言
 * （principal=marketplace，开放争议查询）。{@link Caller#isMerchant()} 等 party/judge/cs 判定对 service callerKind 恒 false
 * （防服务断言冒充终端身份）。
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

    /** 商家或推荐官（OpenDispute / 启动审判：HLD 10.5 Party = 商家/推荐官）。 */
    public Mono<Caller> requireMerchantOrRecommender(ServerHttpRequest request) {
        return resolve(request)
                .filter(c -> c.isMerchant() || c.isRecommender())
                .switchIfEmpty(Mono.error(new TrustException(403, "需要商家或推荐官身份")));
    }

    /** 审判官（投票：HLD 5.5 adjudication panel）。仅信任断言 activeIdentityType=judge；面板成员资格由调用方按资源自查。 */
    public Mono<Caller> requireJudge(ServerHttpRequest request) {
        return resolve(request)
                .filter(Caller::isJudge)
                .switchIfEmpty(Mono.error(new TrustException(403, "需要审判官身份")));
    }

    /** 客服（终审/覆盖判决：HLD §11.2 客服兜底）。仅信任断言 activeIdentityType=customer_service。 */
    public Mono<Caller> requireCustomerService(ServerHttpRequest request) {
        return resolve(request)
                .filter(Caller::isCustomerService)
                .switchIfEmpty(Mono.error(new TrustException(403, "需要客服身份")));
    }

    /** 接受终端商家或指定服务 principal（org 由调用方按已加载资源自查）。开放争议查询用（marketplace 调）。 */
    public Mono<Caller> resolveMerchantOrService(ServerHttpRequest request, String servicePrincipal) {
        return resolve(request)
                .filter(c -> c.isMerchant() || c.isServicePrincipal(servicePrincipal))
                .switchIfEmpty(Mono.error(new TrustException(403, "无权查询争议")));
    }

    /** 接受当事方（商家/推荐官）或指定服务 principal（org 由调用方按已加载资源自查）。审判状态查询用。 */
    public Mono<Caller> resolvePartyOrService(ServerHttpRequest request, String servicePrincipal) {
        return resolve(request)
                .filter(c -> c.isMerchant() || c.isRecommender() || c.isServicePrincipal(servicePrincipal))
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

        /** 审判官（HLD 5.5）。服务断言不可冒充——callerKind=service 恒 false。 */
        public boolean isJudge() {
            return !"service".equalsIgnoreCase(callerKind)
                    && "judge".equalsIgnoreCase(activeIdentityType);
        }

        /** 客服（HLD §11.2 终审）。服务断言不可冒充——callerKind=service 恒 false。 */
        public boolean isCustomerService() {
            return !"service".equalsIgnoreCase(callerKind)
                    && "customer_service".equalsIgnoreCase(activeIdentityType);
        }

        public boolean isService() {
            return "service".equalsIgnoreCase(callerKind);
        }

        public boolean isServicePrincipal(String expectedPrincipal) {
            return isService() && expectedPrincipal != null && expectedPrincipal.equalsIgnoreCase(principal);
        }
    }
}
