package com.grassland.marketplace.event;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "marketplace.outbox")
public record MarketplaceOutboxProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("grassland.marketplace.events") String topic,
        @DefaultValue("50") int batchSize,
        @DefaultValue("8") int maxConcurrency,
        @DefaultValue("10s") Duration ackTimeout,
        @DefaultValue("5m") Duration claimDuration,
        @DefaultValue("1s") Duration initialBackoff,
        @DefaultValue("5m") Duration maxBackoff) {

    public MarketplaceOutboxProperties {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("marketplace.outbox.topic must not be blank");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("marketplace.outbox.batch-size must be positive");
        }
        if (maxConcurrency < 1 || maxConcurrency > batchSize) {
            throw new IllegalArgumentException(
                    "marketplace.outbox.max-concurrency must be between 1 and batch-size");
        }
        requirePositive(ackTimeout, "ack-timeout");
        requirePositive(claimDuration, "claim-duration");
        if (claimDuration.compareTo(ackTimeout) < 0) {
            throw new IllegalArgumentException("marketplace.outbox.claim-duration must not be below ack-timeout");
        }
        requirePositive(initialBackoff, "initial-backoff");
        requirePositive(maxBackoff, "max-backoff");
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("marketplace.outbox.max-backoff must not be below initial-backoff");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("marketplace.outbox." + name + " must be positive");
        }
    }
}
