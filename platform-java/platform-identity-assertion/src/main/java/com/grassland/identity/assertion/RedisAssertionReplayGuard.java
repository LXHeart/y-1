package com.grassland.identity.assertion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

/**
 * Redis 跨副本 replay guard。{@code SET key 1 NX PX ttl} 由 Spring Data 的
 * {@code setIfAbsent(key,value,ttl)} 原子执行；Redis 不可用时 fail-closed。
 */
public final class RedisAssertionReplayGuard implements AssertionReplayGuard.SingleUse {

    private final ReactiveStringRedisTemplate redis;
    private final String keyPrefix;
    private final Clock clock;

    public RedisAssertionReplayGuard(ReactiveStringRedisTemplate redis, String keyPrefix) {
        this(redis, keyPrefix, Clock.systemUTC());
    }

    RedisAssertionReplayGuard(ReactiveStringRedisTemplate redis, String keyPrefix, Clock clock) {
        if (redis == null) {
            throw new IllegalArgumentException("redis template must be non-null");
        }
        this.redis = redis;
        this.keyPrefix = (keyPrefix == null || keyPrefix.isBlank()) ? "grassland:identity-assertion:replay:" : keyPrefix;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    /** Redis 模式禁止同步调用，避免在 WebFlux event-loop 上阻塞。 */
    @Override
    public boolean consumeOnce(String jti, Instant expiresAt) {
        throw new IllegalStateException("Redis replay guard requires consumeOnceReactive");
    }

    @Override
    public Mono<Boolean> consumeOnceReactive(String jti, Instant expiresAt) {
        if (jti == null || jti.isBlank() || expiresAt == null) {
            return Mono.just(false);
        }
        Duration ttl = Duration.between(clock.instant(), expiresAt);
        if (ttl.isZero() || ttl.isNegative()) {
            return Mono.just(false);
        }
        return redis.opsForValue().setIfAbsent(keyPrefix + jti, "1", ttl)
                .defaultIfEmpty(false)
                // 安全边界：共享存储不可达时拒绝断言，绝不静默退回本机内存。
                .onErrorResume(RuntimeException.class, error -> Mono.just(false));
    }
}
