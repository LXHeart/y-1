package com.grassland.edge.security;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Cookie 写请求的全局 Origin/Referer 门禁，覆盖所有 Java 与 legacy BFF 路由。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class EdgeCsrfOriginFilter implements WebFilter {

    private static final Set<HttpMethod> STATE_CHANGING = Set.of(
            HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);
    private static final Pattern COMMA = Pattern.compile(",");
    private static final byte[] FORBIDDEN_BODY =
            "{\"success\":false,\"error\":\"请求来源不被允许。\"}".getBytes(StandardCharsets.UTF_8);

    private final boolean enabled;
    private final Set<String> allowedOrigins;

    @Autowired
    public EdgeCsrfOriginFilter(EdgeSecurityProperties properties) {
        this(properties.csrfEnabled(), properties.allowedOrigins());
    }

    EdgeCsrfOriginFilter(boolean enabled, List<String> allowedOrigins) {
        this.enabled = enabled;
        this.allowedOrigins = normalizeAllowedOrigins(allowedOrigins);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        if (!enabled || !isApiPath(request.getPath().value()) || !STATE_CHANGING.contains(request.getMethod())) {
            return chain.filter(exchange);
        }

        String rawOrigin = request.getHeaders().getFirst(HttpHeaders.ORIGIN);
        String rawReferer = request.getHeaders().getFirst(HttpHeaders.REFERER);
        String candidate;
        if (rawOrigin != null) {
            candidate = normalizeOrigin(rawOrigin);
            if (candidate == null || "null".equalsIgnoreCase(rawOrigin.trim())) {
                return forbidden(exchange);
            }
        } else if (rawReferer != null) {
            candidate = normalizeOrigin(rawReferer);
            if (candidate == null) {
                return forbidden(exchange);
            }
        } else {
            // curl、任务执行器等非浏览器调用没有自动携带 Cookie 的 CSRF 前提。
            return chain.filter(exchange);
        }

        String self = selfOrigin(exchange);
        if (candidate.equals(self) || allowedOrigins.contains(candidate)) {
            return chain.filter(exchange);
        }
        return forbidden(exchange);
    }

    private static boolean isApiPath(String path) {
        return "/api".equals(path) || path.startsWith("/api/");
    }

    private static Set<String> normalizeAllowedOrigins(List<String> configured) {
        Set<String> result = new LinkedHashSet<>();
        for (String item : configured) {
            if (item == null) {
                continue;
            }
            for (String value : COMMA.split(item)) {
                String normalized = normalizeOrigin(value);
                if (normalized != null) {
                    result.add(normalized);
                }
            }
        }
        return Set.copyOf(result);
    }

    private static String selfOrigin(ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        String host = headers.getFirst(HttpHeaders.HOST);
        if (host == null || host.isBlank()) {
            return null;
        }
        String forwardedProto = headers.getFirst("X-Forwarded-Proto");
        String scheme = forwardedProto == null
                ? exchange.getRequest().getURI().getScheme()
                : COMMA.split(forwardedProto, 2)[0].trim().toLowerCase(Locale.ROOT);
        return normalizeOrigin(scheme + "://" + host);
    }

    private static String normalizeOrigin(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (!("http".equals(scheme) || "https".equals(scheme)) || host == null || uri.getUserInfo() != null) {
                return null;
            }
            return new URI(scheme, null, host.toLowerCase(Locale.ROOT), uri.getPort(), null, null, null)
                    .toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Mono<Void> forbidden(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setContentLength(FORBIDDEN_BODY.length);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(FORBIDDEN_BODY)));
    }
}
