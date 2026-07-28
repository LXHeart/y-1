package com.grassland.intelligence.imageanalysis;

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
 * 图片评价 multipart POST 的 body 前门禁（草场 intelligence Slice 6）。镜像 {@code ArticleImagePreflightFilter}，
 * 逐字保持 legacy {@code app.ts} 行为：
 * <ul>
 *   <li>{@code /analyze}、{@code /step/draft}、{@code /export-feishu}：Content-Length ≤ 30MB，超→400「图片上传总大小不能超过 30 MB」。</li>
 *   <li>{@code /analyze}：内存固定窗口 20/60s，key=已登录 accountId 否则 IP；每个响应（含成功）带 {@code RateLimit-*}，
 *       超→429「图片内容提取请求过于频繁，请稍后再试。」。匿名放行（限流按 IP）。</li>
 *   <li>{@code /export-feishu}：硬鉴权（缺断言→401「未登录」），与 legacy {@code requireAuthenticatedUser} 在 body 前一致。</li>
 * </ul>
 */
@Component
public class ImageAnalysisPreflightFilter implements WebFilter, Ordered {

    private static final Set<String> MULTIPART_PATHS = Set.of(
            "/api/image-analysis/analyze",
            "/api/image-analysis/step/draft",
            "/api/image-analysis/export-feishu");
    private static final String ANALYZE_PATH = "/api/image-analysis/analyze";
    private static final String EXPORT_PATH = "/api/image-analysis/export-feishu";
    private static final long MAX_REQUEST_BYTES = 6L * 5 * 1024 * 1024; // 6 张 × 5MB = 30MB
    private static final int RATE_LIMIT = 20;
    private static final long WINDOW_MILLIS = 60_000L;

    private final IntelligenceCallerResolver callers;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public ImageAnalysisPreflightFilter(IntelligenceCallerResolver callers) {
        this(callers, Clock.systemUTC());
    }

    ImageAnalysisPreflightFilter(IntelligenceCallerResolver callers, Clock clock) {
        this.callers = callers;
        this.clock = clock;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!"POST".equals(exchange.getRequest().getMethod().name())
                || !MULTIPART_PATHS.contains(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getPath().value();
        if (requestTooLarge(exchange)) {
            return writeError(exchange, HttpStatus.BAD_REQUEST, "图片上传总大小不能超过 30 MB");
        }
        if (EXPORT_PATH.equals(path)) {
            return callers.resolve(exchange.getRequest())
                    .then(chain.filter(exchange))
                    .onErrorResume(IntelligenceException.class,
                            e -> writeError(exchange, HttpStatus.UNAUTHORIZED, "未登录"));
        }
        if (ANALYZE_PATH.equals(path)) {
            return rateLimitKey(exchange).flatMap(key -> {
                RateDecision decision = rate(key);
                applyHeaders(exchange, decision);
                return decision.allowed()
                        ? chain.filter(exchange)
                        : writeError(exchange, HttpStatus.TOO_MANY_REQUESTS,
                                "图片内容提取请求过于频繁，请稍后再试。");
            });
        }
        return chain.filter(exchange);
    }

    private Mono<String> rateLimitKey(ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .map(caller -> "image-analysis:user:" + caller.accountId())
                .onErrorResume(e -> Mono.just("image-analysis:ip:" + clientIp(exchange)));
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

    private boolean requestTooLarge(ServerWebExchange exchange) {
        MediaType contentType = exchange.getRequest().getHeaders().getContentType();
        if (contentType == null || !MediaType.MULTIPART_FORM_DATA.isCompatibleWith(contentType)) {
            return false;
        }
        return exchange.getRequest().getHeaders().getContentLength() > MAX_REQUEST_BYTES;
    }

    private RateDecision rate(String key) {
        long now = clock.millis();
        DecisionHolder holder = new DecisionHolder();
        windows.compute(key, (k, current) -> {
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

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String message) {
        byte[] bytes = ("{\"success\":false,\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
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
