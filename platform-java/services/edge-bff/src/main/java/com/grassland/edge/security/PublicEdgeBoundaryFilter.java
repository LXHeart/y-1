package com.grassland.edge.security;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
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

/** Edge 公网边界：拒绝内部路径，并无条件清除所有客户端自报的内部身份头。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class PublicEdgeBoundaryFilter implements WebFilter {

    private final Set<String> internalHeaders;

    @Autowired
    public PublicEdgeBoundaryFilter(EdgeSecurityProperties properties) {
        this(properties.internalIdentityHeader());
    }

    PublicEdgeBoundaryFilter() {
        this("X-Grassland-Identity");
    }

    private PublicEdgeBoundaryFilter(String identityHeader) {
        Set<String> headers = new LinkedHashSet<>();
        headers.add(identityHeader);
        headers.add("X-Grassland-Identity");
        headers.add("X-Grassland-Account-Id");
        headers.add("X-Grassland-Active-Identity");
        headers.add("X-Grassland-Session-Token");
        this.internalHeaders = Set.copyOf(headers);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (isInternalPath(exchange.getRequest().getPath().value())) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        ServerHttpRequest sanitized = exchange.getRequest().mutate()
                .headers(headers -> {
                    internalHeaders.forEach(headers::remove);
                    if (!isRefreshRequest(exchange)) {
                        headers.remove(HttpHeaders.AUTHORIZATION);
                    }
                })
                .build();
        return chain.filter(exchange.mutate().request(sanitized).build());
    }

    private static boolean isInternalPath(String path) {
        return "/internal".equals(path) || path.startsWith("/internal/")
                || "/api/internal".equals(path) || path.startsWith("/api/internal/");
    }

    private static boolean isRefreshRequest(ServerWebExchange exchange) {
        return HttpMethod.POST.equals(exchange.getRequest().getMethod())
                && "/api/auth/refresh".equals(exchange.getRequest().getPath().value());
    }
}
