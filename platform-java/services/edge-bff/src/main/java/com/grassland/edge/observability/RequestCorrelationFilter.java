package com.grassland.edge.observability;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Normalizes the public correlation headers before identity assertions are signed or proxied.
 * Invalid client values are replaced, so logs and downstream services cannot be correlated to
 * attacker-controlled arbitrary strings.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestCorrelationFilter implements WebFilter {
    public static final String REQUEST_ID = "X-Request-Id";
    public static final String TRACE_ID = "X-Trace-Id";
    public static final String CORRELATION_ID = "X-Correlation-Id";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = normalizeRequestId(exchange.getRequest().getHeaders().getFirst(REQUEST_ID));
        String traceId = normalizeTraceId(exchange.getRequest().getHeaders().getFirst(TRACE_ID));
        String correlationId = normalizeCorrelationId(
                exchange.getRequest().getHeaders().getFirst(CORRELATION_ID), requestId);

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.set(REQUEST_ID, requestId);
                    headers.set(TRACE_ID, traceId);
                    headers.set(CORRELATION_ID, correlationId);
                })
                .build();
        exchange.getResponse().getHeaders().set(REQUEST_ID, requestId);
        exchange.getResponse().getHeaders().set(TRACE_ID, traceId);
        exchange.getResponse().getHeaders().set(CORRELATION_ID, correlationId);
        return chain.filter(exchange.mutate().request(request).build());
    }

    static String normalizeRequestId(String value) {
        return value != null && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
                ? value.toLowerCase(java.util.Locale.ROOT) : UUID.randomUUID().toString();
    }

    static String normalizeTraceId(String value) {
        if (value != null && value.matches("[0-9a-fA-F]{32}") && !value.matches("0{32}")) {
            return value.toLowerCase(java.util.Locale.ROOT);
        }
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    static String normalizeCorrelationId(String value, String requestId) {
        return value != null && value.matches("[A-Za-z0-9._:-]{1,128}") ? value : requestId;
    }
}
