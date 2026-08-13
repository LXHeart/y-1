package com.grassland.marketplace.event;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisherMetrics {

    private final MeterRegistry registry;
    private final Counter claimed;
    private final Counter published;
    private final Counter staleClaim;
    private final Timer publishDuration;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestPendingAge = new AtomicLong();

    public OutboxPublisherMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.claimed = registry.counter("marketplace.outbox.claimed");
        this.published = registry.counter("marketplace.outbox.published");
        this.staleClaim = registry.counter("marketplace.outbox.stale.claim");
        this.publishDuration = registry.timer("marketplace.outbox.publish.duration");
        Gauge.builder("grassland.outbox.pending", pending, AtomicLong::get).register(registry);
        Gauge.builder("grassland.outbox.oldest.pending.age", oldestPendingAge, AtomicLong::get)
                .baseUnit("seconds")
                .register(registry);
    }

    void claimed() {
        claimed.increment();
    }

    void published(Duration duration) {
        published.increment();
        publishDuration.record(duration);
    }

    void failed(String errorCode, Duration duration) {
        registry.counter("marketplace.outbox.failed", "error_code", errorCode).increment();
        publishDuration.record(duration);
    }

    void staleClaim() {
        staleClaim.increment();
    }

    void backlog(long pendingCount, long oldestPendingAgeSeconds) {
        pending.set(pendingCount);
        oldestPendingAge.set(oldestPendingAgeSeconds);
    }
}
