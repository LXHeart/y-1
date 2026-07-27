package com.grassland.intelligence.articleimage;

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

/** 文章图片 POST 的 body 前鉴权 + 每账号 10 次/60 秒限流。 */
@Component
public class ArticleImagePreflightFilter implements WebFilter, Ordered {

    private static final Set<String> PATHS = Set.of(
            "/api/article-generation/image-recommendations",
            "/api/article-generation/search-images",
            "/api/article-generation/generate-image");
    private static final int LIMIT = 10;
    private static final long WINDOW_MILLIS = 60_000L;
    private static final long MAX_MULTIPART_REQUEST_BYTES = 4L * 5 * 1024 * 1024 + 12L * 32 * 1024;

    private final IntelligenceCallerResolver callers;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public ArticleImagePreflightFilter(IntelligenceCallerResolver callers) {
        this(callers, Clock.systemUTC());
    }

    ArticleImagePreflightFilter(IntelligenceCallerResolver callers, Clock clock) {
        this.callers = callers;
        this.clock = clock;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!"POST".equals(exchange.getRequest().getMethod().name())
                || !PATHS.contains(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        return callers.resolve(exchange.getRequest())
                .onErrorResume(IntelligenceException.class,
                        error -> Mono.error(new AuthenticationPreflightException()))
                .flatMap(caller -> {
                    if (requestTooLarge(exchange)) {
                        return writeError(exchange, HttpStatus.BAD_REQUEST,
                                "图片上传总大小不能超过 20 MB");
                    }
                    RateDecision decision = rate(caller.accountId());
                    applyHeaders(exchange, decision);
                    return decision.allowed()
                            ? chain.filter(exchange)
                            : writeError(exchange, HttpStatus.TOO_MANY_REQUESTS,
                                    "文章创作请求过于频繁，请稍后再试。");
                })
                .onErrorResume(AuthenticationPreflightException.class,
                        error -> writeError(exchange, HttpStatus.UNAUTHORIZED, "未登录"));
    }

    private boolean requestTooLarge(ServerWebExchange exchange) {
        MediaType contentType = exchange.getRequest().getHeaders().getContentType();
        if (contentType == null || !MediaType.MULTIPART_FORM_DATA.isCompatibleWith(contentType)) {
            return false;
        }
        long contentLength = exchange.getRequest().getHeaders().getContentLength();
        return contentLength > MAX_MULTIPART_REQUEST_BYTES;
    }

    private RateDecision rate(String accountId) {
        long now = clock.millis();
        DecisionHolder holder = new DecisionHolder();
        windows.compute(accountId, (key, current) -> {
            if (current == null || current.resetAt() <= now) {
                holder.decision = new RateDecision(true, 1, now + WINDOW_MILLIS);
                return new Window(1, now + WINDOW_MILLIS);
            }
            if (current.count() >= LIMIT) {
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
        exchange.getResponse().getHeaders().set("RateLimit-Limit", String.valueOf(LIMIT));
        exchange.getResponse().getHeaders().set(
                "RateLimit-Remaining", String.valueOf(Math.max(0, LIMIT - decision.count())));
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
