package com.grassland.intelligence.smoke;

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
 * 冒烟端点限流闸门（GL-P0-SEC-002）。
 *
 * <p>{@code /api/intelligence/smoke/chat} 真实消耗平台 Qwen 上游。此前它只要求登录，既不扣积分
 * 也不限流 —— 而其它六个 preflight filter 都按精确路径匹配，没有一个覆盖到它，于是任何登录账号
 * 都能无成本、无节流地驱动上游。本 filter 与 {@code VideoScriptPreflightFilter} 同构，在进入
 * controller 前完成 BFF 断言验签与每账号限流；积分扣减在 controller 内经 {@code CreditsClient} 完成。
 *
 * <p>限流额度低于业务端点（冒烟只用于验证连通性，不是生产用途）。
 */
@Component
public class SmokePreflightFilter implements WebFilter, Ordered {

    static final int MAX_REQUESTS_PER_WINDOW = 5;
    static final long WINDOW_MILLIS = 60_000L;
    private static final String PATH = "/api/intelligence/smoke/chat";

    private final IntelligenceCallerResolver callers;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public SmokePreflightFilter(IntelligenceCallerResolver callers) {
        this(callers, Clock.systemUTC());
    }

    SmokePreflightFilter(IntelligenceCallerResolver callers, Clock clock) {
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
                || !PATH.equals(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> allow(caller.accountId())
                        ? chain.filter(exchange)
                        : writeError(exchange, HttpStatus.TOO_MANY_REQUESTS, "冒烟请求过于频繁，请稍后再试。"))
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
