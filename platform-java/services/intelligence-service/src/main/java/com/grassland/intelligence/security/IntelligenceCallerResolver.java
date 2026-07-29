package com.grassland.intelligence.security;

import com.grassland.identity.assertion.IdentityAssertionSigner;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * intelligence 调用者解析（草场 intelligence Slice 1 / HLD 7.4）：**仅**信任 edge-bff 签发的
 * {@code X-Grassland-Identity} 断言，无 cookie 回退（intelligence 是纯下游，识人完全靠 BFF 断言）。
 *
 * <p>两类断言（HLD 11.1「服务身份」，镜像 {@code FinanceCallerResolver}）：
 * <ul>
 *   <li><b>用户断言</b>（edge-bff 签发，{@code callerKind=user/null}）— 终端商家/推荐官，识人靠 {@code accountId}。</li>
 *   <li><b>服务断言</b>（领域服务现签，{@code callerKind=service} + {@code principal}）— 如 marketplace 中转读
 *       履约附件（Slice 11 Stage 1）。带 {@code organizationId} 上下文供 org 级授权。</li>
 * </ul>
 *
 * <p>断言缺/失效 → 401；{@link #requireMerchant}/{@link #requireRecommender} 额外要求对应活动身份 → 403；
 * {@link #requireServicePrincipal} 要求指定服务 principal → 403。
 * 冒烟端点（{@code /api/intelligence/smoke/*}）只需 {@link #resolve}（任意登录用户）。
 *
 * <p>防冒充：服务断言 {@code activeIdentityType=null} 且 {@code callerKind=service}，{@link Caller#isMerchant}/
 * {@link Caller#isRecommender} 对其恒为 false——服务断言不可凭 activeIdentityType 冒充终端商家/推荐官。
 * 资源级授权仍须服务端自查（HLD 7.4 末句）。
 */
@Component
public class IntelligenceCallerResolver {

    /** 受信任的履约编排服务 principal（marketplace 经 IntelligenceMediaClient 中转读附件，Slice 11）。 */
    public static final String MARKETPLACE_SERVICE = "marketplace";

    private final IdentityAssertionSigner signer;
    private final String headerName;

    public IntelligenceCallerResolver(IdentityAssertionSigner signer,
                                      @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName) {
        this.signer = signer;
        this.headerName = headerName;
    }

    public Mono<Caller> resolve(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(headerName);
        if (header == null || header.isBlank()) {
            return Mono.error(new IntelligenceException(401, "未登录"));
        }
        return Mono.justOrEmpty(signer.verify(header, Instant.now()))
                .map(a -> new Caller(a.accountId(), a.activeIdentityType(), a.sessionToken(),
                        a.organizationId(), a.permissionTier(), a.callerKind(), a.principal()))
                .switchIfEmpty(Mono.error(new IntelligenceException(401, "未登录")));
    }

    public Mono<Caller> requireMerchant(ServerHttpRequest request) {
        return resolve(request)
                .filter(Caller::isMerchant)
                .switchIfEmpty(Mono.error(new IntelligenceException(403, "需要商家身份")));
    }

    public Mono<Caller> requireRecommender(ServerHttpRequest request) {
        return resolve(request)
                .filter(Caller::isRecommender)
                .switchIfEmpty(Mono.error(new IntelligenceException(403, "需要推荐官身份")));
    }

    /**
     * 仅指定服务 principal（Slice 11 Stage 1：履约附件中转读）。非该 principal 的服务断言、终端用户断言
     * 一律 403——这些端点不对浏览器/终端用户开放。缺/失效断言仍由 {@link #resolve} 返回 401。
     */
    public Mono<Caller> requireServicePrincipal(ServerHttpRequest request, String expectedPrincipal) {
        return resolve(request)
                .filter(caller -> caller.isServicePrincipal(expectedPrincipal))
                .switchIfEmpty(Mono.error(new IntelligenceException(403, "需要服务身份")));
    }

    /** 断言解析出的调用者。{@code activeIdentityType} 为 null=消费者；{@code callerKind}/{@code principal} 区分用户 vs 服务断言（HLD 11.1）。 */
    public record Caller(String accountId, String activeIdentityType, String sessionToken,
                         String organizationId, String permissionTier,
                         String callerKind, String principal) {
        /** 终端商家用户。服务断言（callerKind=service）恒为 false——防止服务断言凭 activeIdentityType 冒充商家。 */
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

        /** 是否为指定服务 principal 的服务断言（大小写不敏感）。 */
        public boolean isServicePrincipal(String expectedPrincipal) {
            return isService() && expectedPrincipal != null && expectedPrincipal.equalsIgnoreCase(principal);
        }
    }
}
