package com.grassland.intelligence.douyin;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
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

/**
 * Douyin {@code POST /api/douyin/extract-video} 限流（草场 GL-P3-MEDIA-001）。
 *
 * <p>逐字延续 legacy {@code app.ts} 的 {@code douyin-extract} 成本闸：固定窗口 <b>20 次 / 60 秒</b>，
 * 超过返回 {@code 429 {success:false,error:"提取请求过于频繁，请稍后再试。"}}，每次请求带
 * {@code RateLimit-Limit/Remaining/Reset}。置于 resolve 抓取之前——提取会触发最多 3 次抖音页面抓取
 * （HTTP 解析失败时进入 Java Playwright 浏览器阶段），是无鉴权端点里成本最高的入口。
 *
 * <p>提取是公开端点（无登录），与 legacy {@code getRateLimitKey} 匿名分支一致按 <b>IP</b> 限流：
 * 取 {@code X-Forwarded-For} 最右一跳（避免左侧伪造绕过，同 {@code MediaUploadTicketPreflightFilter}）。
 */
@Component
public class DouyinExtractPreflightFilter implements WebFilter, Ordered {

    private static final String EXTRACT_PATH = "/api/douyin/extract-video";
    private static final int RATE_LIMIT = 20;
    private static final long WINDOW_MILLIS = 60_000L;
    private static final String RATE_LIMIT_MESSAGE = "提取请求过于频繁，请稍后再试。";

    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public DouyinExtractPreflightFilter() {
        this(Clock.systemUTC());
    }

    DouyinExtractPreflightFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!"POST".equals(exchange.getRequest().getMethod().name())
                || !EXTRACT_PATH.equals(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        RateDecision decision = rate(bucketKey(exchange));
        applyHeaders(exchange, decision);
        return decision.allowed()
                ? chain.filter(exchange)
                : writeError(exchange, HttpStatus.TOO_MANY_REQUESTS, RATE_LIMIT_MESSAGE);
    }

    private RateDecision rate(String key) {
        long now = clock.millis();
        DecisionHolder holder = new DecisionHolder();
        windows.compute(key, (ignored, current) -> {
            if (current == null || current.resetAt() <= now) {
                holder.decision = new RateDecision(true, 1, now + WINDOW_MILLIS);
                return new Window(1, now + WINDOW_MILLIS);
            }
            if (current.count() >= RATE_LIMIT) {
                holder.decision = new RateDecision(false, current.count(), current.resetAt());
                return current;
            }
            int next = current.count() + 1;
            holder.decision = new RateDecision(true, next, current.resetAt());
            return new Window(next, current.resetAt());
        });
        if (windows.size() > 1_000) {
            windows.entrySet().removeIf(entry -> entry.getValue().resetAt() <= now);
        }
        return holder.decision;
    }

    private void applyHeaders(ServerWebExchange exchange, RateDecision decision) {
        long resetSeconds = Math.max(0L, (decision.resetAt() - clock.millis() + 999L) / 1_000L);
        exchange.getResponse().getHeaders().set("RateLimit-Limit", String.valueOf(RATE_LIMIT));
        exchange.getResponse().getHeaders().set(
                "RateLimit-Remaining", String.valueOf(Math.max(0, RATE_LIMIT - decision.count())));
        exchange.getResponse().getHeaders().set("RateLimit-Reset", String.valueOf(resetSeconds));
    }

    private static String bucketKey(ServerWebExchange exchange) {
        return "douyin-extract:ip:" + clientIp(exchange);
    }

    private static String clientIp(ServerWebExchange exchange) {
        // edge-bff/nginx 可能把客户端伪造值放在链左侧；取最右一跳（离服务最近）避免轮换左侧值绕过限流。
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.lastIndexOf(',');
            return (comma >= 0 ? forwarded.substring(comma + 1) : forwarded).trim();
        }
        return exchange.getRequest().getRemoteAddress() == null
                ? "unknown"
                : exchange.getRequest().getRemoteAddress().getHostString();
    }

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String message) {
        byte[] bytes = ("{\"success\":false,\"error\":\"" + message + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private record Window(int count, long resetAt) {}

    private record RateDecision(boolean allowed, int count, long resetAt) {}

    private static final class DecisionHolder {
        private RateDecision decision;
    }
}
