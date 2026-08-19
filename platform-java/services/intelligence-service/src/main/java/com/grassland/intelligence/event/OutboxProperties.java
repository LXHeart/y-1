package com.grassland.intelligence.event;

import com.grassland.messaging.outbox.OutboxSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 薄绑定层：只负责 intelligence.outbox 前缀与默认 topic；
 * 默认值回退与钳制规则单源在 {@link OutboxSettings}（platform-messaging）。
 */
@ConfigurationProperties(prefix = "intelligence.outbox")
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
        topic = topic == null || topic.isBlank() ? "grassland.intelligence.events" : topic;
    }

    public OutboxSettings settings() {
        return new OutboxSettings(
                topic, enabled, pollIntervalMs, batchSize, maxConcurrency,
                ackTimeoutMs, claimLeaseMs, initialBackoffMs, maxBackoffMs);
    }
}
