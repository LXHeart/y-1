package com.grassland.intelligence.guesttrial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * {@link GuestTrialRateLimiter} 单元测试（任务书 #36 B1）：短窗/日限触发与 Redis 故障 fail-closed。
 * Redis 用 mock（INCR/EXPIRE 打桩），语义在真 Redis 上与 SET NX 同族（原子计数 + TTL）。
 */
@SuppressWarnings("unchecked")
class GuestTrialRateLimiterTest {

    private ReactiveStringRedisTemplate redisTemplate;
    private ReactiveValueOperations<String, String> valueOps;
    private GuestTrialProperties props;
    private GuestTrialRateLimiter limiter;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        valueOps = mock(ReactiveValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        props = new GuestTrialProperties();
        limiter = new GuestTrialRateLimiter(redisTemplate, props);
    }

    private void stubIncrements(long burst, long daily) {
        when(valueOps.increment(anyString()))
                .thenReturn(Mono.just(burst))
                .thenReturn(Mono.just(daily));
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
    }

    @Test
    @DisplayName("两层都未超 → 放行（empty）")
    void withinLimitsPasses() {
        stubIncrements(3L, 10L);
        StepVerifier.create(limiter.check("abcd1234abcd1234", "2026-08-17"))
                .verifyComplete();
    }

    @Test
    @DisplayName("分钟短窗超过 10 → 拒绝（IP_BURST）")
    void burstExceededRejected() {
        stubIncrements(11L, 5L);
        StepVerifier.create(limiter.check("abcd1234abcd1234", "2026-08-17"))
                .assertNext(exceeded -> {
                    assertThat(exceeded.layer()).isEqualTo(GuestTrialRateLimiter.Layer.IP_BURST);
                    assertThat(exceeded.limit()).isEqualTo(props.getIpBurstPerMinute());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("IP 日上限超过 30 → 拒绝（IP_DAILY，cookie 清除也刷不穿的兜底闸门）")
    void dailyExceededRejected() {
        stubIncrements(2L, 31L);
        StepVerifier.create(limiter.check("abcd1234abcd1234", "2026-08-17"))
                .assertNext(exceeded ->
                        assertThat(exceeded.layer()).isEqualTo(GuestTrialRateLimiter.Layer.IP_DAILY))
                .verifyComplete();
    }

    @Test
    @DisplayName("Redis 故障 → 上抛异常（调用方 fail-closed 兜底为拒绝），绝不静默放行")
    void redisFailurePropagatesForFailClosed() {
        when(valueOps.increment(anyString())).thenReturn(Mono.error(new RuntimeException("redis down")));
        // limiter 契约：错误上抛（非 empty 放行）；controller 的 onErrorResume 把它兜底为 429 拒绝。
        StepVerifier.create(limiter.check("abcd1234abcd1234", "2026-08-17"))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    @DisplayName("首次计数写 TTL（当日/短窗各自过期）")
    void firstIncrementSetsTtl() {
        when(valueOps.increment(anyString())).thenReturn(Mono.just(1L));
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
        StepVerifier.create(limiter.check("abcd1234abcd1234", "2026-08-17"))
                .verifyComplete();
        org.mockito.Mockito.verify(redisTemplate).expire(anyString(), eq(Duration.ofSeconds(60)));
        org.mockito.Mockito.verify(redisTemplate).expire(anyString(), eq(Duration.ofDays(1)));
    }
}
