package com.grassland.intelligence.videorecreation;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Set;
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
 * 视频改编出图 POST 的 body 前鉴权 + 限流（草场 intelligence Slice 9）。镜像 legacy
 * {@code server/src/routes/video-recreation.ts}：router 级 10 次/60 秒 + 批量端点额外 2 次/60 秒。
 *
 * <p>两个独立 per-account 固定窗口桶：{@code global}（10/60s，覆盖 4 个端点）+ {@code batch}（2/60s，仅 2 个
 * {@code -all-} 批量端点，批量请求两桶都消费）。先 resolve caller（缺/失效断言 → 401「未登录」），再判限流，
 * 每响应带 {@code RateLimit-*} 头（报 global 桶）。小 JSON body 无需 Content-Length 门禁。
 * {@code order = HIGHEST_PRECEDENCE + 4}（+1/+2/+3 已被 ArticleImage/ImageAnalysis/MediaUploadTicket 占）。
 */
@Component
public class VideoRecreationPreflightFilter implements WebFilter, Ordered {

    private static final Set<String> PATHS = Set.of(
            "/api/video-recreation/generate-asset-image",
            "/api/video-recreation/generate-all-asset-images",
            "/api/video-recreation/generate-scene-image",
            "/api/video-recreation/generate-all-scene-images");
    private static final Set<String> BATCH_PATHS = Set.of(
            "/api/video-recreation/generate-all-asset-images",
            "/api/video-recreation/generate-all-scene-images");
    private static final int GLOBAL_LIMIT = 10;
    private static final int BATCH_LIMIT = 2;
    private static final long WINDOW_MILLIS = 60_000L;

    private final IntelligenceCallerResolver callers;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> global = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Window> batch = new ConcurrentHashMap<>();

    @Autowired
    public VideoRecreationPreflightFilter(IntelligenceCallerResolver callers) {
        this(callers, Clock.systemUTC());
    }

    VideoRecreationPreflightFilter(IntelligenceCallerResolver callers, Clock clock) {
        this.callers = callers;
        this.clock = clock;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 4;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!"POST".equals(exchange.getRequest().getMethod().name()) || !PATHS.contains(path)) {
            return chain.filter(exchange);
        }
        return callers.resolve(exchange.getRequest())
                .onErrorResume(IntelligenceException.class,
                        error -> Mono.error(new AuthenticationPreflightException()))
                .flatMap(caller -> {
                    RateDecision globalDecision = rate(global, caller.accountId(), GLOBAL_LIMIT);
                    applyHeaders(exchange, globalDecision, GLOBAL_LIMIT);
                    RateDecision batchDecision = BATCH_PATHS.contains(path)
                            ? rate(batch, caller.accountId(), BATCH_LIMIT)
                            : null;
                    boolean allowed = globalDecision.allowed()
                            && (batchDecision == null || batchDecision.allowed());
                    return allowed
                            ? chain.filter(exchange)
                            : writeError(exchange, HttpStatus.TOO_MANY_REQUESTS,
                                    "视频改编请求过于频繁，请稍后再试。");
                })
                .onErrorResume(AuthenticationPreflightException.class,
                        error -> writeError(exchange, HttpStatus.UNAUTHORIZED, "未登录"));
    }

    private RateDecision rate(ConcurrentHashMap<String, Window> windows, String accountId, int limit) {
        long now = clock.millis();
        DecisionHolder holder = new DecisionHolder();
        windows.compute(accountId, (key, current) -> {
            if (current == null || current.resetAt() <= now) {
                holder.decision = new RateDecision(true, 1, now + WINDOW_MILLIS);
                return new Window(1, now + WINDOW_MILLIS);
            }
            if (current.count() >= limit) {
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

    private void applyHeaders(ServerWebExchange exchange, RateDecision decision, int limit) {
        long resetSeconds = Math.max(0L, (decision.resetAt() - clock.millis() + 999L) / 1_000L);
        exchange.getResponse().getHeaders().set("RateLimit-Limit", String.valueOf(limit));
        exchange.getResponse().getHeaders().set(
                "RateLimit-Remaining", String.valueOf(Math.max(0, limit - decision.count())));
        exchange.getResponse().getHeaders().set("RateLimit-Reset", String.valueOf(resetSeconds));
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
