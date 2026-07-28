package com.grassland.marketplace.event;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TrustEventConsumerMetrics {

    private final Map<TrustEventProcessingResult, Counter> resultCounters;
    private final Counter failed;
    private final Counter retry;
    private final Counter recovered;
    private final Counter recoveryFailed;

    public TrustEventConsumerMetrics(MeterRegistry registry) {
        Map<TrustEventProcessingResult, Counter> counters = new EnumMap<>(TrustEventProcessingResult.class);
        for (TrustEventProcessingResult result : TrustEventProcessingResult.values()) {
            counters.put(
                    result,
                    registry.counter(
                            "marketplace.trust.consumer.records",
                            "outcome",
                            result.name().toLowerCase(java.util.Locale.ROOT)));
        }
        this.resultCounters = Map.copyOf(counters);
        this.failed = registry.counter("marketplace.trust.consumer.records", "outcome", "failed");
        this.retry = registry.counter("marketplace.trust.consumer.retry");
        this.recovered = registry.counter("marketplace.trust.consumer.recovered");
        this.recoveryFailed = registry.counter("marketplace.trust.consumer.recovery.failed");
    }

    void record(TrustEventProcessingResult result) {
        resultCounters.get(result).increment();
    }

    void failed() {
        failed.increment();
    }

    public void retry() {
        retry.increment();
    }

    public void recovered() {
        recovered.increment();
    }

    public void recoveryFailed() {
        recoveryFailed.increment();
    }
}
