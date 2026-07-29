package com.grassland.intelligence.media;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
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
 * media-reference 鉴权三步上传的频率闸门（草场 Slice 8 收尾）。镜像 {@code ImageAnalysisPreflightFilter}：
 *
 * <p>仅 {@code POST /api/media/upload-tickets} 命中；其余 media 路径（confirm / 签名读 / 删除）直接放行。
 * 上传票据 body 是小 JSON，重字节走 presigned PUT 直达对象存储，不经本端点——故本闸门只做请求频率控制，
 * 不做 body 大小门禁（已有配额 {@code insertIfQuotaAllowed} 兜底）。
 *
 * <ul>
 *   <li>key=已登录 accountId，否则回退 IP（取 {@code X-Forwarded-For} 最右一跳，避免左侧伪造绕过）。</li>
 *   <li>内存固定窗口 10/60s；每个响应（含成功）带 {@code RateLimit-*}，超→429「媒体上传请求过于频繁，请稍后再试。」。</li>
 *   <li>匿名请求按 IP 限流后仍放行（交由 controller 的鉴权返回 401）。</li>
 * </ul>
 */
@Component
public class MediaUploadTicketPreflightFilter implements WebFilter, Ordered {

    private static final String UPLOAD_TICKETS_PATH = "/api/media/upload-tickets";
    private static final int RATE_LIMIT = 10;
    private static final long WINDOW_MILLIS = 60_000L;

    private final IntelligenceCallerResolver callers;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public MediaUploadTicketPreflightFilter(IntelligenceCallerResolver callers) {
        this(callers, Clock.systemUTC());
    }

    MediaUploadTicketPreflightFilter(IntelligenceCallerResolver callers, Clock clock) {
        this.callers = callers;
        this.clock = clock;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 3;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!"POST".equals(exchange.getRequest().getMethod().name())
                || !UPLOAD_TICKETS_PATH.equals(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        return rateLimitKey(exchange).flatMap(key -> {
            RateDecision decision = rate(key);
            applyHeaders(exchange, decision);
            return decision.allowed()
                    ? chain.filter(exchange)
                    : writeError(exchange, HttpStatus.TOO_MANY_REQUESTS, "媒体上传请求过于频繁，请稍后再试。");
        });
    }

    private Mono<String> rateLimitKey(ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .map(caller -> "media:upload:user:" + caller.accountId())
                .onErrorResume(e -> Mono.just("media:upload:ip:" + clientIp(exchange)));
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
