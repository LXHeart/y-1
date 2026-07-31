package com.grassland.identity.notification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 通知消费计量（镜像 marketplace {@code TrustEventConsumerMetrics}）。 */
@Component
public class NotificationConsumerMetrics {

    private final Map<NotificationProcessingResult, Counter> resultCounters;
    private final Counter failed;

    public NotificationConsumerMetrics(MeterRegistry registry) {
        Map<NotificationProcessingResult, Counter> counters = new EnumMap<>(NotificationProcessingResult.class);
        for (NotificationProcessingResult result : NotificationProcessingResult.values()) {
            counters.put(
                    result,
                    registry.counter(
                            "identity.notification.consumer.records",
                            "outcome",
                            result.name().toLowerCase(java.util.Locale.ROOT)));
        }
        this.resultCounters = Map.copyOf(counters);
        this.failed = registry.counter("identity.notification.consumer.records", "outcome", "failed");
    }

    void record(NotificationProcessingResult result) {
        resultCounters.get(result).increment();
    }

    void failed() {
        failed.increment();
    }
}
