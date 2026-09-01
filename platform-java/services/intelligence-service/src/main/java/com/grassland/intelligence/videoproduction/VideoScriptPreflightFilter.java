package com.grassland.intelligence.videoproduction;

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
 * 视频脚本大请求的 body 前置闸门。
 *
 * <p>该端点允许最多 10MB base64 JSON。若只在 controller 里鉴权，WebFlux 会先完整聚合 body，
 * 未登录客户端可在收到 401 前消耗大量堆内存。本 filter 在 body 解码前完成 BFF 断言验签，并恢复
 * legacy {@code video-production} 的每账号 10 次/分钟限流；其它端点不受影响。
 */
@Component
public class VideoScriptPreflightFilter implements WebFilter, Ordered {

    static final int MAX_REQUESTS_PER_WINDOW = 10;
    static final long WINDOW_MILLIS = 60_000L;
    /** 任务书 #64 卡3/卡6：分镜与建任务同为 10MB 级 base64 大请求，一并前置闸门。 */
    private static final java.util.Set<String> PATHS = java.util.Set.of(
            "/api/video-production/generate-script",
            "/api/video-production/storyboard",
            "/api/video-production/tasks");

    private final IntelligenceCallerResolver callers;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public VideoScriptPreflightFilter(IntelligenceCallerResolver callers) {
        this(callers, Clock.systemUTC());
    }

    VideoScriptPreflightFilter(IntelligenceCallerResolver callers, Clock clock) {
        this.callers = callers;
        this.clock = clock;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!"POST".equals(exchange.getRequest().getMethod().name())
                || !PATHS.contains(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> allow(caller.accountId())
                        ? chain.filter(exchange)
                        : writeError(exchange, HttpStatus.TOO_MANY_REQUESTS, "视频制作请求过于频繁，请稍后再试。"))
                .onErrorResume(IntelligenceException.class,
                        error -> writeError(exchange, HttpStatus.UNAUTHORIZED, "未登录"));
    }

    private boolean allow(String accountId) {
        long now = clock.millis();
        Decision decision = new Decision();
        windows.compute(accountId, (key, current) -> {
            if (current == null || current.resetAt() <= now) {
                decision.allowed = true;
                return new Window(1, now + WINDOW_MILLIS);
            }
            if (current.count() >= MAX_REQUESTS_PER_WINDOW) {
                decision.allowed = false;
                return current;
            }
            decision.allowed = true;
            return new Window(current.count() + 1, current.resetAt());
        });
        if (windows.size() > 1_000) {
            windows.entrySet().removeIf(entry -> entry.getValue().resetAt() <= now);
        }
        return decision.allowed;
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

    private static final class Decision {
        private boolean allowed;
    }
}
