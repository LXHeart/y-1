package com.grassland.finance.security;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Prevents finance internal endpoints from being reached through a forwarding proxy. */
@Component
public class InternalRequestBoundaryFilter implements WebFilter {

    private static final String[] FORWARDED_HEADERS = {
        "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "forwarded"
    };

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (!path.startsWith("/internal/")) {
            return chain.filter(exchange);
        }

        HttpHeaders headers = exchange.getRequest().getHeaders();
        for (String name : FORWARDED_HEADERS) {
            if (headers.getFirst(name) != null) {
                return writeNotFound(exchange.getResponse());
            }
        }
        return chain.filter(exchange);
    }

    private static Mono<Void> writeNotFound(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.NOT_FOUND);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = "{\"success\":false,\"error\":\"内部接口不可经代理访问\"}"
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
