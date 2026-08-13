package com.grassland.edge.internalassertion;

import com.grassland.edge.proxy.UpstreamResolver;
import com.grassland.edge.proxy.EdgeRoutingProperties;
import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionProperties;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * BFF 内部身份断言签发（HLD 7.4 + GL-P0-ASSERT-001 keyring 模式）。
 *
 * <p>对<b>所有</b>请求：剥离客户端伪造的内部身份 Header（{@code internalHeaderDenylist}）——防御纵深。
 * 对<b>内部 Java 上游</b>（见 {@link UpstreamResolver#isInternalUpstream}）：解析当前账号，
 * 按目标上游选对应的签名钥，签发短时断言并附加 {@code X-Grassland-Identity}。匿名/未解析 → 不附断言（下游视作匿名）。
 *
 * <p>目标受众映射：从 upstream 名推导 audience（如 {@code identity} → {@code grassland-identity}），
 * 按 {@code (purpose=USER, audience)} 查签名钥。缺钥时告警且不附断言（fail-closed：下游 401/cookie 回退）。
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

    private static final Logger log = LoggerFactory.getLogger(InternalAssertionFilter.class);

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
            boolean accessTokenAuthenticated =
                    exchange.getAttribute(AccessTokenFilter.RESOLVED_IDENTITY_ATTRIBUTE) != null;
            requestMono = resolveIdentity(exchange)
                    .map(identity -> stripAndSign(request, identity, method, path, accessTokenAuthenticated))
                    .defaultIfEmpty(stripInternalHeaders(request));
        }
        return requestMono.flatMap(mutated -> chain.filter(exchange.mutate().request(mutated).build()));
    }

    /**
     * 解析当前请求身份：Bearer access token 优先（移动端），cookie session 回退（Web 端）。
     * 两者都失败 → empty（匿名放行）。
     *
     * <p>refresh/revoke 端点跳过 Bearer（它们用 refresh_token 作 Bearer，格式不同于 access token，
     * identity 侧自鉴权）。
     */
    private Mono<ResolvedIdentity> resolveIdentity(ServerWebExchange exchange) {
        ResolvedIdentity accessTokenIdentity =
                exchange.getAttribute(AccessTokenFilter.RESOLVED_IDENTITY_ATTRIBUTE);
        if (accessTokenIdentity != null) {
            return Mono.just(accessTokenIdentity);
        }
        return resolver.resolve(exchange.getRequest());
    }

    /** 剥离 denylist 头（防御纵深：清掉客户端伪造的内部身份头）。 */
    private ServerHttpRequest stripInternalHeaders(ServerHttpRequest request) {
        return request.mutate()
                .headers(headers -> properties.internalHeaderDenylist().forEach(headers::remove))
                .build();
    }

    /** 剥离 denylist 头后附加签发的断言头（按目标上游选钥）。 */
    private ServerHttpRequest stripAndSign(ServerHttpRequest request, ResolvedIdentity identity,
                                           String method, String path, boolean accessTokenAuthenticated) {
        String targetAudience = resolveTargetAudience(method, path);
        if (targetAudience == null) {
            log.warn("Unable to resolve target audience for method={} path={}; not attaching assertion", method, path);
            return stripInternalHeaders(request);
        }

        IdentityAssertion base = buildBaseAssertion(identity, request, accessTokenAuthenticated);
        String token;
        try {
            token = signer.sign(base, targetAudience);
        } catch (Exception e) {
            log.warn("Failed to sign assertion for audience={}: {} (not attaching assertion)", targetAudience, e.getMessage());
            return stripInternalHeaders(request);
        }

        return request.mutate()
                .headers(headers -> {
                    properties.internalHeaderDenylist().forEach(headers::remove);
                    headers.add(properties.headerName(), token);
                })
                .build();
    }

    /**
     * 从 upstream 名解析目标受众。
     *
     * <p>映射规则：{@code <service>} → {@code grassland-<service>}（如 identity → grassland-identity）。
     * fail-closed 返回 null（不附断言）。
     */
    private String resolveTargetAudience(String method, String path) {
        String upstreamName = upstreamResolver.resolveUpstreamName(method, path);
        if (upstreamName == null || EdgeRoutingProperties.FAIL_CLOSED.equals(upstreamName)) {
            return null;
        }
        return "grassland-" + upstreamName;
    }

    /**
     * 构造断言基础字段（不含 envelope claims：issuer/keyId/jti 由 signer 填充，audience 由 targetAudience 决定）。
     */
    private IdentityAssertion buildBaseAssertion(ResolvedIdentity identity, ServerHttpRequest request,
                                                  boolean accessTokenAuthenticated) {
        Instant now = Instant.now();
        // authMethod：移动端 Bearer → access-token；Web cookie → cookie-session
        String authMethod = accessTokenAuthenticated ? "access-token" : "cookie-session";
        return new IdentityAssertion(
                identity.accountId(),
                identity.activeIdentityType(),
                identity.sessionToken(),
                identity.organizationId(),
                identity.permissionTier(),
                authMethod,
                // 认证强度与重认证时刻取自 identity_session（V7）——此前硬编码 level1/null，
                // 导致 trust 客服终审的 MFA 近期性校验恒失败（403）。
                identity.authStrength() == null ? "level1" : identity.authStrength(),
                identity.reauthenticatedAt(),
                headerOrUuid(request, "X-Request-Id"),
                headerOrUuid(request, "X-Trace-Id"),
                // 默认 audience 会由 signer.sign(assertion, targetAudience) 重写为目标受众。
                properties.audience(), now, now.plus(properties.ttl()),
                "user", null,
                // 平台角色（app_users.role）：与业务身份正交，供下游做平台侧授权
                // （trust 客服终审等）。此前未签入，导致客服身份无法认定。
                identity.role(),
                // envelope claims 由 signer 填充
                null, null, null);
    }

    private static String headerOrUuid(ServerHttpRequest request, String headerName) {
        String value = request.getHeaders().getFirst(headerName);
        return (value == null || value.isBlank()) ? UUID.randomUUID().toString() : value;
    }
}
