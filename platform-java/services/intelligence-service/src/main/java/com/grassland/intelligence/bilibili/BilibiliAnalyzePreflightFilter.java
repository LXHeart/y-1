package com.grassland.intelligence.bilibili;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
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
 * Bilibili {@code POST /api/bilibili/analyze-video} 的 body 前鉴权 + 限流（草场 Slice 13 Stage 5）。
 *
 * <p>逐字延续 legacy {@code app.ts} 的 {@code bilibili-analyze} 防滥用成本闸：固定窗口
 * <b>20 次 / 60 秒</b>，超过返回 {@code 429 {success:false,error:"视频内容提取请求过于频繁，请稍后再试。"}}，
 * 每次已鉴权请求（包含 429）带 {@code RateLimit-Limit/Remaining/Reset}。置于 controller / CreditsClient / Qwen
 * 之前，防止重放同一尚未过期的 proxy token 消耗积分或模型成本。
 *
 * <p>该端点与 legacy 一致要求登录：先验 edge-bff 断言，缺/失效→401「未登录」；桶键仅为
 * {@code accountId}（与 legacy {@code getRateLimitKey} 在已登录时选择 user key 的语义一致）。
 */
@Component
public class BilibiliAnalyzePreflightFilter implements WebFilter, Ordered {

    private static final String ANALYZE_PATH = "/api/bilibili/analyze-video";
    private static final int RATE_LIMIT = 20;
    private static final long WINDOW_MILLIS = 60_000L;
    private static final String RATE_LIMIT_MESSAGE = "视频内容提取请求过于频繁，请稍后再试。";

    private final IntelligenceCallerResolver callers;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public BilibiliAnalyzePreflightFilter(IntelligenceCallerResolver callers) {
        this(callers, Clock.systemUTC());
    }

    BilibiliAnalyzePreflightFilter(IntelligenceCallerResolver callers, Clock clock) {
        this.callers = callers;
        this.clock = clock;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!"POST".equals(exchange.getRequest().getMethod().name())
                || !ANALYZE_PATH.equals(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        return callers.resolve(exchange.getRequest())
                .onErrorResume(IntelligenceException.class, error -> Mono.error(new AuthenticationPreflightException()))
                .flatMap(caller -> {
                    RateDecision decision = rate(bucketKey(caller.accountId()));
                    applyHeaders(exchange, decision);
                    return decision.allowed()
                            ? chain.filter(exchange)
                            : writeError(exchange, HttpStatus.TOO_MANY_REQUESTS, RATE_LIMIT_MESSAGE);
                })
                .onErrorResume(AuthenticationPreflightException.class,
                        error -> writeError(exchange, HttpStatus.UNAUTHORIZED, "未登录"));
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

    private static String bucketKey(String accountId) {
        return "bilibili-analyze:user:" + accountId;
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

    private static final class AuthenticationPreflightException extends RuntimeException {}

    private static final class DecisionHolder {
        private RateDecision decision;
    }
}
