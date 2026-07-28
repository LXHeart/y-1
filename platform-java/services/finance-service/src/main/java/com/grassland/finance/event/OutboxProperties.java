package com.grassland.finance.event;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "finance.outbox")
public record OutboxProperties(
        String topic,
        boolean enabled,
        long pollIntervalMs,
        int batchSize,
        int maxConcurrency,
        long ackTimeoutMs,
        long claimLeaseMs,
        long initialBackoffMs,
        long maxBackoffMs) {

    public OutboxProperties {
        if (topic == null || topic.isBlank()) {
            topic = "grassland.finance.events";
        }
        pollIntervalMs = positive(pollIntervalMs, 2_000);
        batchSize = Math.max(batchSize, 1);
        maxConcurrency = Math.max(maxConcurrency, 1);
        ackTimeoutMs = positive(ackTimeoutMs, 10_000);
        claimLeaseMs = Math.max(positive(claimLeaseMs, 300_000), ackTimeoutMs);
        initialBackoffMs = positive(initialBackoffMs, 1_000);
        maxBackoffMs = Math.max(positive(maxBackoffMs, 60_000), initialBackoffMs);
    }

    public Duration ackTimeout() {
        return Duration.ofMillis(ackTimeoutMs);
    }

    public Duration claimLease() {
        return Duration.ofMillis(claimLeaseMs);
    }

    private static long positive(long value, long fallback) {
        return value > 0 ? value : fallback;
    }
}
