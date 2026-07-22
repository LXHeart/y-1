package com.grassland.identity.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LoginRateLimiter {
    private final long windowMs;
    private final int ipMax;
    private final int accountIpMax;
    private final Map<String, WindowCounter> ipCounters = new ConcurrentHashMap<>();
    private final Map<String, WindowCounter> accountIpCounters = new ConcurrentHashMap<>();

    public LoginRateLimiter(
        @Value("${identity.security.login-rate-limit.window-ms:60000}") long windowMs,
        @Value("${identity.security.login-rate-limit.ip-max:10}") int ipMax,
        @Value("${identity.security.login-rate-limit.account-ip-max:5}") int accountIpMax) {
        this.windowMs = windowMs;
        this.ipMax = ipMax;
        this.accountIpMax = accountIpMax;
    }

    public CheckResult check(String ip, String email) {
        if (ip == null || ip.isBlank()) {
            return new CheckResult(true, 0, 0);
        }
        WindowCounter ipCounter = ipCounters.computeIfAbsent(ip, k -> new WindowCounter(windowMs));
        int ipCount = ipCounter.current();
        int accountCount = 0;
        if (email != null && !email.isBlank()) {
            String key = ip + ":" + email.trim().toLowerCase();
            WindowCounter accountCounter = accountIpCounters.computeIfAbsent(key, k -> new WindowCounter(windowMs));
            accountCount = accountCounter.current();
        }
        boolean allowed = ipCount < ipMax && accountCount < accountIpMax;
        return new CheckResult(allowed, Math.max(0, ipMax - ipCount), Math.max(0, accountIpMax - accountCount));
    }

    public void recordOutcome(String ip, String email, boolean authFailure) {
        if (ip == null || ip.isBlank()) {
            return;
        }
        if (authFailure) {
            return;
        }
        WindowCounter ipCounter = ipCounters.get(ip);
        if (ipCounter != null) {
            ipCounter.decrement();
        }
        if (email != null && !email.isBlank()) {
            String key = ip + ":" + email.trim().toLowerCase();
            WindowCounter accountCounter = accountIpCounters.get(key);
            if (accountCounter != null) {
                accountCounter.decrement();
            }
        }
    }

    public record CheckResult(boolean allowed, int ipRemaining, int accountRemaining) {}

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
