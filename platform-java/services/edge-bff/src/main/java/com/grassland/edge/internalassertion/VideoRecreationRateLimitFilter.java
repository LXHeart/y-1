package com.grassland.edge.internalassertion;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * 保持视频改编路由族的 router-wide 限流语义（草场 Slice 9 review 修复）。
 *
 * <p>{@code /api/video-recreation} 的全部 POST 共用每账号 10 次/60 秒。多个精确路由
 * 在 BFF 分流前按 session 解析出的 accountId 维护该族唯一桶；下游的
 * intelligence filter 仍保留，以保护绕过 BFF 的内部直连及 batch 额外 2/min 规则。
 *
 * <p>仅在 BFF 已启 session 直读时装配；匿名/未解析请求不计数，由下游鉴权返回 401。
 */
@Component
@ConditionalOnProperty(name = "edge.identity.from-database-url", havingValue = "true")
public class VideoRecreationRateLimitFilter implements WebFilter, Ordered {

    private static final String PREFIX = "/api/video-recreation";
    private static final int LIMIT = 10;
    private static final long WINDOW_MILLIS = 60_000L;
    private static final String MESSAGE = "视频改编请求过于频繁，请稍后再试。";

    private final SessionIdentityResolver identities;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * 生产构造器。**必须显式标注 {@code @Autowired}**：本类还有一个测试用的可注入 Clock 构造器，
     * 两个候选构造器且都没标注时 Spring 只会去找无参构造器，启动即 `NoSuchMethodException: <init>()`。
     * （Slice 9 引入本类时未标注，因 edge-bff 镜像一直是旧构建才未暴露；Slice 12 Stage 5 重建镜像时崩在启动。）
     */
    @Autowired
    public VideoRecreationRateLimitFilter(SessionIdentityResolver identities) {
        this(identities, Clock.systemUTC());
    }

    VideoRecreationRateLimitFilter(SessionIdentityResolver identities, Clock clock) {
        this.identities = identities;
        this.clock = clock;
    }

    @Override
    public int getOrder() {
        // 在内部断言签发前计数，确保全部精确路由共享同一桶。
        return Ordered.HIGHEST_PRECEDENCE + 99;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isVideoRecreationPost(exchange)) {
            return chain.filter(exchange);
        }
        return identities.resolve(exchange.getRequest())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(maybeIdentity -> {
                    if (maybeIdentity.isEmpty()) {
                        return chain.filter(exchange);
                    }
                    RateDecision decision = rate(maybeIdentity.get().accountId());
                    applyHeaders(exchange, decision);
                    return decision.allowed()
                            ? chain.filter(exchange)
                            : write429(exchange);
                });
    }

    private static boolean isVideoRecreationPost(ServerWebExchange exchange) {
        return exchange.getRequest().getMethod() != null
                && "POST".equals(exchange.getRequest().getMethod().name())
                && (PREFIX.equals(exchange.getRequest().getPath().value())
                        || exchange.getRequest().getPath().value().startsWith(PREFIX + "/"));
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

    private static Mono<Void> write429(ServerWebExchange exchange) {
        byte[] bytes = ("{\"success\":false,\"error\":\"" + MESSAGE + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
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
