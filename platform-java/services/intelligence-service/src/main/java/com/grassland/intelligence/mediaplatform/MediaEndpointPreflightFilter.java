package com.grassland.intelligence.mediaplatform;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Preserves the legacy fixed-window limits for public media extraction and delivery endpoints. */
@Component
public class MediaEndpointPreflightFilter implements WebFilter, Ordered {
    private static final long WINDOW_MILLIS = 60_000L;
    private static final List<EndpointRule> RULES = List.of(
            new EndpointRule("POST", "/api/bilibili/extract-video", true, "bilibili-extract", 20,
                    "提取请求过于频繁，请稍后再试。"),
            new EndpointRule("GET", "/api/bilibili/proxy", false, "bilibili-proxy", 120,
                    "视频预览请求过于频繁，请稍后再试。"),
            new EndpointRule("GET", "/api/bilibili/download", false, "bilibili-download", 30,
                    "视频下载请求过于频繁，请稍后再试。"),
            new EndpointRule("GET", "/api/bilibili/analysis-media", false, "bilibili-analysis-media", 300,
                    "分析视频读取请求过于频繁，请稍后再试。"),
            new EndpointRule("GET", "/api/douyin/proxy", false, "douyin-proxy", 120,
                    "视频预览请求过于频繁，请稍后再试。"),
            new EndpointRule("GET", "/api/douyin/download", false, "douyin-download", 30,
                    "视频下载请求过于频繁，请稍后再试。"),
            new EndpointRule("GET", "/api/douyin/audio", false, "douyin-audio", 20,
                    "音频提取请求过于频繁，请稍后再试。"),
            new EndpointRule("GET", "/api/douyin/analysis-media", false, "douyin-analysis-media", 300,
                    "分析视频读取请求过于频繁，请稍后再试。"));

    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public MediaEndpointPreflightFilter() {
        this(Clock.systemUTC());
    }

    MediaEndpointPreflightFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        EndpointRule rule = ruleFor(exchange.getRequest().getMethod().name(), exchange.getRequest().getPath().value());
        if (rule == null) return chain.filter(exchange);

        RateDecision decision = rate(rule, clientIp(exchange));
        applyHeaders(exchange, rule, decision);
        return decision.allowed() ? chain.filter(exchange) : writeError(exchange, rule.message());
    }

    EndpointRule ruleFor(String method, String path) {
        return RULES.stream().filter(rule -> rule.matches(method, path)).findFirst().orElse(null);
    }

    private RateDecision rate(EndpointRule rule, String clientIp) {
        long now = clock.millis();
        DecisionHolder holder = new DecisionHolder();
        windows.compute(rule.id() + ":ip:" + clientIp, (ignored, current) -> {
            if (current == null || current.resetAt() <= now) {
                holder.decision = new RateDecision(true, 1, now + WINDOW_MILLIS);
                return new Window(1, now + WINDOW_MILLIS);
            }
            if (current.count() >= rule.limit()) {
                holder.decision = new RateDecision(false, current.count(), current.resetAt());
                return current;
            }
            int next = current.count() + 1;
            holder.decision = new RateDecision(true, next, current.resetAt());
            return new Window(next, current.resetAt());
        });
        if (windows.size() > 1_000) windows.entrySet().removeIf(entry -> entry.getValue().resetAt() <= now);
        return holder.decision;
    }

    private void applyHeaders(ServerWebExchange exchange, EndpointRule rule, RateDecision decision) {
        long resetSeconds = Math.max(0L, (decision.resetAt() - clock.millis() + 999L) / 1_000L);
        exchange.getResponse().getHeaders().set("RateLimit-Limit", String.valueOf(rule.limit()));
        exchange.getResponse().getHeaders().set(
                "RateLimit-Remaining", String.valueOf(Math.max(0, rule.limit() - decision.count())));
        exchange.getResponse().getHeaders().set("RateLimit-Reset", String.valueOf(resetSeconds));
    }

    private static String clientIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.lastIndexOf(',');
            return (comma >= 0 ? forwarded.substring(comma + 1) : forwarded).trim();
        }
        return exchange.getRequest().getRemoteAddress() == null
                ? "unknown"
                : exchange.getRequest().getRemoteAddress().getHostString();
    }

    private Mono<Void> writeError(ServerWebExchange exchange, String message) {
        byte[] bytes = ("{\"success\":false,\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    record EndpointRule(String method, String path, boolean exact, String id, int limit, String message) {
        boolean matches(String actualMethod, String actualPath) {
            if (!method.equals(actualMethod)) return false;
            return exact ? path.equals(actualPath) : path.equals(actualPath) || actualPath.startsWith(path + "/");
        }
    }

    private record Window(int count, long resetAt) {}
    private record RateDecision(boolean allowed, int count, long resetAt) {}
    private static final class DecisionHolder { private RateDecision decision; }
}
