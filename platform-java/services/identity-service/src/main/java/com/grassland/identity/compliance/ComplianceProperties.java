package com.grassland.identity.compliance;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "identity.compliance")
public record ComplianceProperties(
        boolean enabled,
        long pollIntervalMs,
        int batchSize,
        int maxConcurrency,
        long claimLeaseSeconds,
        int maxAttempts,
        long initialBackoffSeconds,
        long maxBackoffSeconds,
        long exportTtlHours,
        long piiRetentionDays,
        long upstreamTimeoutMs) {

    public ComplianceProperties {
        pollIntervalMs = positive(pollIntervalMs, 5_000);
        batchSize = bounded(batchSize, 10, 1, 100);
        maxConcurrency = bounded(maxConcurrency, 2, 1, 16);
        claimLeaseSeconds = positive(claimLeaseSeconds, 300);
        maxAttempts = bounded(maxAttempts, 8, 1, 30);
        initialBackoffSeconds = positive(initialBackoffSeconds, 5);
        maxBackoffSeconds = Math.max(positive(maxBackoffSeconds, 3_600), initialBackoffSeconds);
        exportTtlHours = bounded(exportTtlHours, 24, 1, 168);
        // ADR D10 provisional default. Keep configurable until legal review is complete.
        piiRetentionDays = bounded(piiRetentionDays, 180, 1, 3_650);
        upstreamTimeoutMs = positive(upstreamTimeoutMs, 10_000);
    }

    public Duration claimLease() {
        return Duration.ofSeconds(claimLeaseSeconds);
    }

    public Duration exportTtl() {
        return Duration.ofHours(exportTtlHours);
    }

    public Duration piiRetention() {
        return Duration.ofDays(piiRetentionDays);
    }

    public Duration upstreamTimeout() {
        return Duration.ofMillis(upstreamTimeoutMs);
    }

    public Duration retryBackoff(int attemptCount) {
        int exponent = Math.max(0, Math.min(attemptCount - 1, 30));
        long multiplier = 1L << exponent;
        long seconds = multiplier > maxBackoffSeconds / initialBackoffSeconds
                ? maxBackoffSeconds
                : Math.min(initialBackoffSeconds * multiplier, maxBackoffSeconds);
        return Duration.ofSeconds(seconds);
    }

    private static long positive(long value, long fallback) {
        return value > 0 ? value : fallback;
    }

    private static int bounded(int value, int fallback, int minimum, int maximum) {
        int resolved = value > 0 ? value : fallback;
        return Math.max(minimum, Math.min(resolved, maximum));
    }

    private static long bounded(long value, long fallback, long minimum, long maximum) {
        long resolved = value > 0 ? value : fallback;
        return Math.max(minimum, Math.min(resolved, maximum));
    }
}
