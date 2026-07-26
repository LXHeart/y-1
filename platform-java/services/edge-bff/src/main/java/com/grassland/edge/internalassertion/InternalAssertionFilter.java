package com.grassland.edge.internalassertion;

import com.grassland.edge.proxy.UpstreamResolver;
import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionProperties;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * BFF 内部身份断言签发（HLD 7.4）。
 *
 * <p>对<b>所有</b>请求：剥离客户端伪造的内部身份 Header（{@code internalHeaderDenylist}）——防御纵深。
 * 对<b>内部 Java 上游</b>（非 legacy，见 {@link UpstreamResolver#isInternalUpstream}）：解析当前账号，签发短时断言并附加
 * {@code X-Grassland-Identity}。匿名/未解析 → 不附断言（下游视作匿名）。
 *
 * <p>签发的真实保护是下游 HMAC 验签（客户端无法伪造合法断言）；剥离是 belt-and-suspenders。
 * 下游验签失败会回退 cookie（identity-service additive 消费），降级而非宕机。
 *
 * <p>{@code @ConditionalOnProperty(edge.identity.from-database-url)}：未启用直读 session 的环境（纯代理）不装本 filter，
 * edge-bff 仍正常启动。signer/properties 由 {@code identity-assertion.enabled=true} 提供，二者须同开。
 */
@Component
@ConditionalOnProperty(name = "edge.identity.from-database-url", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class InternalAssertionFilter implements WebFilter {

    private final SessionIdentityResolver resolver;
    private final IdentityAssertionSigner signer;
    private final IdentityAssertionProperties properties;
    private final UpstreamResolver upstreamResolver;

    public InternalAssertionFilter(SessionIdentityResolver resolver, IdentityAssertionSigner signer,
                                   IdentityAssertionProperties properties, UpstreamResolver upstreamResolver) {
        this.resolver = resolver;
        this.signer = signer;
        this.properties = properties;
        this.upstreamResolver = upstreamResolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String method = request.getMethod().name();
        String path = request.getURI().getPath();

        // 每个请求只 mutate 一次（原请求的 .mutate() 给出可变头部副本）：剥离内部头，必要时附加断言。
        Mono<ServerHttpRequest> requestMono;
        if (!upstreamResolver.isInternalUpstream(method, path)) {
            requestMono = Mono.just(stripInternalHeaders(request));
        } else {
            requestMono = resolver.resolve(request)
                    .map(identity -> stripAndSign(request, signer.sign(buildAssertion(identity, exchange))))
                    .defaultIfEmpty(stripInternalHeaders(request));
        }
        return requestMono.flatMap(mutated -> chain.filter(exchange.mutate().request(mutated).build()));
    }

    /** 剥离 denylist 头（防御纵深：清掉客户端伪造的内部身份头）。 */
    private ServerHttpRequest stripInternalHeaders(ServerHttpRequest request) {
        return request.mutate()
                .headers(headers -> properties.internalHeaderDenylist().forEach(headers::remove))
                .build();
    }

    /** 剥离 denylist 头后附加签发的断言头。 */
    private ServerHttpRequest stripAndSign(ServerHttpRequest request, String token) {
        return request.mutate()
                .headers(headers -> {
                    properties.internalHeaderDenylist().forEach(headers::remove);
                    headers.add(properties.headerName(), token);
                })
                .build();
    }

    private IdentityAssertion buildAssertion(ResolvedIdentity identity, ServerWebExchange exchange) {
        Instant now = Instant.now();
        return new IdentityAssertion(
                identity.accountId(),
                identity.activeIdentityType(),
                identity.sessionToken(),
                identity.organizationId(),
                identity.permissionTier(),
                "cookie-session",
                // 认证强度与重认证时刻取自 identity_session（V7）——此前硬编码 level1/null，
                // 导致 trust 客服终审的 MFA 近期性校验恒失败（403）。
                identity.authStrength() == null ? "level1" : identity.authStrength(),
                identity.reauthenticatedAt(),
                headerOrUuid(exchange, "X-Request-Id"),
                headerOrUuid(exchange, "X-Trace-Id"),
                properties.audience(),
                now,
                now.plus(properties.ttl()),
                "user", null);
    }

    private static String headerOrUuid(ServerWebExchange exchange, String headerName) {
        String value = exchange.getRequest().getHeaders().getFirst(headerName);
        return (value == null || value.isBlank()) ? UUID.randomUUID().toString() : value;
    }
}
