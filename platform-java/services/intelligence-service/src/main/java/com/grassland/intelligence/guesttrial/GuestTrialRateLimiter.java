package com.grassland.intelligence.guesttrial;

import java.time.Duration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 游客试用 IP 双层限流（R2）：日上限（所有 capability 合计）+ 分钟短窗（挡脚本重试）。
 * Redis INCR + 首次设 TTL（镜像断言 replay 防护的 Redis 用法）。
 *
 * <p><b>fail-closed</b>：Redis 不可用时拒绝（429），绝不静默放行——镜像 {@code RedisAssertionReplayGuard}
 * 的安全边界语义。返回 {@link Exceeded} 描述哪层超限（供审计 outcome），{@link Mono#empty} = 放行。
 */
@Component
public class GuestTrialRateLimiter {

    /** 限流终局（审计用）。 */
    public enum Layer {
        IP_DAILY("ip_daily"),
        IP_BURST("ip_burst");

        private final String outcome;

        Layer(String outcome) {
            this.outcome = outcome;
        }

        public String outcome() {
            return outcome;
        }
    }

    public record Exceeded(Layer layer, int limit) {}

    private final ReactiveStringRedisTemplate redis;
    private final GuestTrialProperties props;

    public GuestTrialRateLimiter(ReactiveStringRedisTemplate redis, GuestTrialProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /**
     * 计数并判定：先短窗后日限（短窗挡住的重试根本不该消耗检查成本）。两键各自 INCR，首次写带 TTL。
     * 空 = 放行；{@link Exceeded} = 拒绝；Redis 异常 = 拒绝（fail-closed，包装为 burst 超限语义）。
     */
    public Mono<Exceeded> check(String ipHash, String dayKey) {
        return increment(props.getRateLimitKeyPrefix() + "sw:" + ipHash + ":" + minuteWindow(),
                        Duration.ofSeconds(60))
                .flatMap(burst -> checkDaily(burst, ipHash, dayKey));
    }

    private Mono<Exceeded> checkDaily(long burst, String ipHash, String dayKey) {
        if (burst > props.getIpBurstPerMinute()) {
            return Mono.just(new Exceeded(Layer.IP_BURST, props.getIpBurstPerMinute()));
        }
        return increment(props.getRateLimitKeyPrefix() + "day:" + ipHash + ":" + dayKey,
                        Duration.ofDays(1))
                .flatMap(daily -> daily > props.getIpDailyLimit()
                        ? Mono.just(new Exceeded(Layer.IP_DAILY, props.getIpDailyLimit()))
                        : Mono.empty());
    }

    /**
     * INCR + 首见 TTL。Redis 故障 → Mono.error（调用方转 429）——不静默放行。
     * 注意：Redis 故障时本方法可能已对短窗 INCR（若第一个键成功、第二个失败）——限流键计数偏差可接受，
     * 语义只往更严的方向漂移（多计不多放）。
     */
    private Mono<Long> increment(String key, Duration ttl) {
        return redis.opsForValue().increment(key)
                .flatMap(count -> count == 1L
                        ? redis.expire(key, ttl).thenReturn(count)
                        : Mono.just(count));
    }

    private static long minuteWindow() {
        return System.currentTimeMillis() / 60_000L;
    }
}
