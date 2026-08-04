package com.grassland.identity.mobile;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * /api/auth/refresh 限流（GL-P3-IDENTITY-001）。结构复制 {@code security/LoginRateLimiter}：
 * 进程内固定窗口，IP 总闸 + IP-token 闸。键用 token 的 SHA-256 hex（内存不落明文 token）。
 *
 * <p>成功刷新不占预算（{@link #recordOutcome} 递减），只有失败/滥用消耗窗口——与登录限流同姿态。
 * 多副本下各自计数（与 LoginRateLimiter 相同的已接受姿态，单实例部署现状）。
 */
@Component
public class RefreshRateLimiter {
    private final long windowMs;
    private final int ipMax;
    private final int tokenIpMax;
    private final Map<String, WindowCounter> ipCounters = new ConcurrentHashMap<>();
    private final Map<String, WindowCounter> tokenIpCounters = new ConcurrentHashMap<>();

    public RefreshRateLimiter(
            @Value("${identity.security.refresh-rate-limit.window-ms:60000}") long windowMs,
            @Value("${identity.security.refresh-rate-limit.ip-max:20}") int ipMax,
            @Value("${identity.security.refresh-rate-limit.token-ip-max:10}") int tokenIpMax) {
        this.windowMs = windowMs;
        this.ipMax = ipMax;
        this.tokenIpMax = tokenIpMax;
    }

    public CheckResult check(String ip, String tokenHash) {
        if (ip == null || ip.isBlank()) {
            return new CheckResult(true, 0, 0);
        }
        WindowCounter ipCounter = ipCounters.computeIfAbsent(ip, k -> new WindowCounter(windowMs));
        int ipCount = ipCounter.current();
        int tokenCount = 0;
        if (tokenHash != null && !tokenHash.isBlank()) {
            String key = ip + ":" + tokenHash;
            WindowCounter tokenCounter = tokenIpCounters.computeIfAbsent(key, k -> new WindowCounter(windowMs));
            tokenCount = tokenCounter.current();
        }
        boolean allowed = ipCount < ipMax && tokenCount < tokenIpMax;
        return new CheckResult(allowed, Math.max(0, ipMax - ipCount), Math.max(0, tokenIpMax - tokenCount));
    }

    public void recordOutcome(String ip, String tokenHash, boolean authFailure) {
        if (ip == null || ip.isBlank() || authFailure) {
            return;
        }
        WindowCounter ipCounter = ipCounters.get(ip);
        if (ipCounter != null) {
            ipCounter.decrement();
        }
        if (tokenHash != null && !tokenHash.isBlank()) {
            String key = ip + ":" + tokenHash;
            WindowCounter tokenCounter = tokenIpCounters.get(key);
            if (tokenCounter != null) {
                tokenCounter.decrement();
            }
        }
    }

    public record CheckResult(boolean allowed, int ipRemaining, int tokenRemaining) {}

    static class WindowCounter {
        private final long windowMs;
        private long windowStart;
        private final AtomicInteger count = new AtomicInteger(0);

        WindowCounter(long windowMs) {
            this.windowMs = windowMs;
            this.windowStart = System.currentTimeMillis();
        }

        int current() {
            long now = System.currentTimeMillis();
            if (now - windowStart > windowMs) {
                windowStart = now;
                count.set(0);
            }
            return count.incrementAndGet();
        }

        void decrement() {
            count.updateAndGet(v -> Math.max(0, v - 1));
        }
    }
}
