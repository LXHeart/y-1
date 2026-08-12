package com.grassland.edge.internalassertion;

import com.grassland.edge.proxy.UpstreamResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Consumes mobile access tokens at the edge and exposes only a resolved identity downstream.
 * Raw credentials are never proxied, except on refresh/revoke where Identity owns the bearer contract.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public final class AccessTokenFilter implements WebFilter {

    static final String RESOLVED_IDENTITY_ATTRIBUTE = AccessTokenFilter.class.getName() + ".identity";

    private final ObjectProvider<AccessTokenIdentityResolver> resolverProvider;
    private final UpstreamResolver upstreamResolver;

    public AccessTokenFilter(ObjectProvider<AccessTokenIdentityResolver> resolverProvider,
                             UpstreamResolver upstreamResolver) {
        this.resolverProvider = resolverProvider;
        this.upstreamResolver = upstreamResolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String method = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getURI().getPath();
        if (isTokenLifecycleEndpoint(method, path)
                && "identity".equals(upstreamResolver.resolveUpstreamName(method, path))) {
            return chain.filter(exchange);
        }

        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null) {
            return chain.filter(exchange);
        }

        ServerWebExchange sanitized = stripAuthorization(exchange);
        if (!upstreamResolver.isInternalUpstream(method, path)) {
            return chain.filter(sanitized);
        }

        AccessTokenIdentityResolver resolver = resolverProvider.getIfAvailable();
        if (resolver == null || !isBearer(authorization)) {
            return unauthorized(sanitized);
        }
        return resolver.resolve(exchange.getRequest())
                .flatMap(identity -> {
                    sanitized.getAttributes().put(RESOLVED_IDENTITY_ATTRIBUTE, identity);
                    return chain.filter(sanitized);
                })
                .switchIfEmpty(Mono.defer(() -> unauthorized(sanitized)));
    }

    private static ServerWebExchange stripAuthorization(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.remove(HttpHeaders.AUTHORIZATION))
                .build();
        return exchange.mutate().request(request).build();
    }

    private static Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    private static boolean isBearer(String authorization) {
        return authorization.length() > 7
                && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                && !authorization.substring(7).trim().isEmpty();
    }

    private static boolean isTokenLifecycleEndpoint(String method, String path) {
        if (!HttpMethod.POST.name().equals(method)) {
            return false;
        }
        return "/api/auth/refresh".equals(path) || "/api/auth/revoke".equals(path);
    }
}
