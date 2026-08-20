package com.grassland.messaging.outbox;

import java.time.Duration;

/**
 * 事务 outbox 发布参数：默认值与钳制规则的单一定义（原四服务 compact constructor 各一份）。
 *
 * <p>
 * 服务侧保留一个薄 {@code @ConfigurationProperties} record 负责绑定前缀与默认 topic， 再委托
 * {@code settings()} 构造本类——校验逻辑只在这里，漂移即编译期可见。
 */
public record OutboxSettings(String topic, boolean enabled, long pollIntervalMs, int batchSize, int maxConcurrency,
		long ackTimeoutMs, long claimLeaseMs, long initialBackoffMs, long maxBackoffMs) {

	public OutboxSettings {
		if (topic == null || topic.isBlank()) {
			throw new IllegalArgumentException("outbox topic must not be blank");
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
