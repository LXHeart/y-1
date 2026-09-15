package com.grassland.identity.notification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 通知消费计量（镜像 marketplace {@code TrustEventConsumerMetrics}；任务书 #103 C103-14 按
 * outcome 拆开）：processed / duplicate / intentionally_ignored / contract_rejected
 * / recipient_unavailable / delivery_failed。日志与指标都不含通知正文。
 */
@Component
public class NotificationConsumerMetrics {

	private final Map<NotificationProcessingResult, Counter> resultCounters;
	private final Counter deliveryFailed;

	public NotificationConsumerMetrics(MeterRegistry registry) {
		Map<NotificationProcessingResult, Counter> counters = new EnumMap<>(NotificationProcessingResult.class);
		for (NotificationProcessingResult result : NotificationProcessingResult.values()) {
			counters.put(result,
					registry.counter("identity.notification.consumer.records", "outcome", outcomeTag(result)));
		}
		this.resultCounters = Map.copyOf(counters);
		this.deliveryFailed = registry.counter("identity.notification.consumer.records", "outcome", "delivery_failed");
	}

	/** IGNORED 的可观测名是 intentionally_ignored（策略性忽略不是静默丢失）。 */
	static String outcomeTag(NotificationProcessingResult result) {
		return result == NotificationProcessingResult.IGNORED
				? "intentionally_ignored"
				: result.name().toLowerCase(Locale.ROOT);
	}

	void record(NotificationProcessingResult result) {
		resultCounters.get(result).increment();
	}

	/** 处理链抛错（DB/依赖故障，非契约错误）——事务已回滚，等待重投。 */
	void deliveryFailed() {
		deliveryFailed.increment();
	}
}
