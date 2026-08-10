package com.grassland.identity.kyb;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "identity.kyb.document-analysis")
public record KybDocumentAnalysisProperties(
        boolean enabled,
        int batchSize,
        int maxConcurrency,
        Duration claimLease,
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff) {

    public KybDocumentAnalysisProperties {
        batchSize = batchSize > 0 ? batchSize : 20;
        maxConcurrency = maxConcurrency > 0 ? maxConcurrency : 4;
        claimLease = positive(claimLease, Duration.ofMinutes(2));
        maxAttempts = maxAttempts > 0 ? maxAttempts : 5;
        initialBackoff = positive(initialBackoff, Duration.ofSeconds(2));
        maxBackoff = positive(maxBackoff, Duration.ofMinutes(2));
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}

