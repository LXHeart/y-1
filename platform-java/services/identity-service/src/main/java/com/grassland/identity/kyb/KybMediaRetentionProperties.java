package com.grassland.identity.kyb;

import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "identity.kyb.retention")
public record KybMediaRetentionProperties(
        boolean enabled,
        long pollIntervalMs,
        int batchSize,
        int maxConcurrency,
        long claimLeaseMs,
        long liveLeaseSeconds,
        long renewAheadSeconds,
        long initialBackoffMs,
        long maxBackoffMs,
        long approvedRetentionDays,
        long rejectedRetentionDays) {

    public KybMediaRetentionProperties {
        pollIntervalMs = positive(pollIntervalMs, 2_000);
        batchSize = Math.max(batchSize, 1);
        maxConcurrency = Math.max(maxConcurrency, 1);
        claimLeaseMs = positive(claimLeaseMs, 60_000);
        liveLeaseSeconds = bounded(liveLeaseSeconds, 604_800, 60, 2_592_000);
        renewAheadSeconds = bounded(renewAheadSeconds, 172_800, 1, liveLeaseSeconds - 1);
        initialBackoffMs = positive(initialBackoffMs, 1_000);
        maxBackoffMs = Math.max(positive(maxBackoffMs, 60_000), initialBackoffMs);
        approvedRetentionDays = bounded(approvedRetentionDays, 2_555, 1, 3_650);
        rejectedRetentionDays = bounded(rejectedRetentionDays, 365, 1, 3_650);
    }

    public Duration claimLease() {
        return Duration.ofMillis(claimLeaseMs);
    }

    public Duration renewAhead() {
        return Duration.ofSeconds(renewAheadSeconds);
    }

    public Instant terminalDeadline(String decision, Instant now) {
        long days = "approved".equals(decision) ? approvedRetentionDays : rejectedRetentionDays;
        return now.plus(Duration.ofDays(days));
    }

    private static long positive(long value, long fallback) {
        return value > 0 ? value : fallback;
    }

    private static long bounded(long value, long fallback, long minimum, long maximum) {
        long resolved = value > 0 ? value : fallback;
        return Math.max(minimum, Math.min(resolved, maximum));
    }
}
