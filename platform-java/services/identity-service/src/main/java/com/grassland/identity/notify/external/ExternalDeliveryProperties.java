package com.grassland.identity.notify.external;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "identity.external-delivery")
public record ExternalDeliveryProperties(
        boolean enabled,
        int batchSize,
        int maxConcurrency,
        Duration claimLease,
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        Duration sendTimeout,
        String challengeSecret) {
    public ExternalDeliveryProperties {
        batchSize = batchSize > 0 ? batchSize : 50;
        maxConcurrency = maxConcurrency > 0 ? maxConcurrency : 8;
        claimLease = positive(claimLease, Duration.ofMinutes(1));
        maxAttempts = maxAttempts > 0 ? maxAttempts : 5;
        initialBackoff = positive(initialBackoff, Duration.ofSeconds(1));
        maxBackoff = positive(maxBackoff, Duration.ofMinutes(1));
        sendTimeout = positive(sendTimeout, Duration.ofSeconds(15));
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}

