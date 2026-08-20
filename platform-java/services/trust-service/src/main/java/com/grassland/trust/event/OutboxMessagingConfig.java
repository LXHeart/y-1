package com.grassland.trust.event;

import com.grassland.messaging.outbox.OutboxPublisher;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.messaging.outbox.OutboxSchema;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 事务 outbox 接线：逻辑单源在 platform-messaging，这里只声明本服务的 表名、配置前缀与日志 owner。调度属性名
 * trust.outbox.poll-interval-ms 是服务私有配置面，@Scheduled 注解必须留在服务侧。
 */
@Configuration
public class OutboxMessagingConfig {

	@Bean
	public OutboxRepository outboxRepository(DatabaseClient db) {
		return new OutboxRepository(db, OutboxSchema.bigintJsonb("trust_outbox"));
	}

	@Bean
	public OutboxPublisher outboxPublisher(OutboxRepository repository,
			ObjectProvider<KafkaTemplate<String, String>> kafka, MeterRegistry meterRegistry,
			OutboxProperties properties) {
		return new OutboxPublisher(repository, kafka.getIfAvailable(), properties.settings(), "trust", meterRegistry);
	}

	/**
	 * 驱动 {@link OutboxPublisher#publishPending()} 的调度壳（原 @Component publisher
	 * 的 @Scheduled）。
	 */
	static class OutboxPublishScheduler {

		private final OutboxPublisher publisher;

		OutboxPublishScheduler(OutboxPublisher publisher) {
			this.publisher = publisher;
		}

		@Scheduled(fixedDelayString = "${trust.outbox.poll-interval-ms:2000}")
		void publishPending() {
			publisher.publishPending();
		}
	}

	@Bean
	public OutboxPublishScheduler outboxPublishScheduler(OutboxPublisher publisher) {
		return new OutboxPublishScheduler(publisher);
	}
}
