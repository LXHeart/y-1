package com.grassland.identity.event;

import com.grassland.messaging.EventContractException;

import com.grassland.identity.notification.NotificationEventConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * identity 通知消费者的幂等表（{@code identity_inbox}）。草场 Slice 12 Stage 2。
 *
 * <p>
 * 镜像 marketplace {@code InboxRepository}：{@code recordIfAbsent} 用
 * {@code ON CONFLICT DO NOTHING} 插入，冲突（同
 * {@code (consumer_name, event_id)}）时回查校验内容是否一致—— 同 ID 异内容（payload SHA-256 不符）抛
 * {@link EventContractException}，进 DLT，不静默覆盖。
 */
@Component
public class InboxRepository {

	private final DatabaseClient db;

	public InboxRepository(DatabaseClient db) {
		this.db = db;
	}

	/**
	 * @return {@code true} 本次插入了一行（新事件）；{@code false} 已处理过（幂等命中）；error = 契约冲突。
	 */
	public Mono<Boolean> recordIfAbsent(String consumerName, ConsumerRecord<String, String> record,
			IdentityEventEnvelope envelope, String payloadSha256) {
		return db.sql("""
				INSERT INTO identity_inbox
				    (consumer_name, event_id, event_type, aggregate_type, aggregate_id,
				     payload_sha256, source_topic, source_partition, source_offset)
				VALUES (:consumerName, :eventId, :eventType, :aggregateType, :aggregateId,
				        :payloadSha256, :topic, :partition, :offset)
				ON CONFLICT DO NOTHING
				RETURNING event_id
				""").bind("consumerName", consumerName).bind("eventId", envelope.eventId())
				.bind("eventType", envelope.eventType()).bind("aggregateType", envelope.aggregateType())
				.bind("aggregateId", envelope.aggregateId()).bind("payloadSha256", payloadSha256)
				.bind("topic", record.topic()).bind("partition", record.partition()).bind("offset", record.offset())
				.map((row, metadata) -> true).one()
				.switchIfEmpty(validateExisting(consumerName, envelope, payloadSha256));
	}

	/**
	 * 仅在单测里失败注入时使用：模拟「领域写（notification 插入）失败」以证明 inbox 行随之回滚。 主路径不经此方法——消费者在
	 * {@link NotificationEventConsumer} 的同一事务里完成 inbox + 通知插入。
	 */
	public Mono<Long> countByConsumer(String consumerName) {
		return db.sql("SELECT COUNT(*)::bigint AS c FROM identity_inbox WHERE consumer_name = :consumerName")
				.bind("consumerName", consumerName).map(row -> row.get("c", Long.class)).one();
	}

	private Mono<Boolean> validateExisting(String consumerName, IdentityEventEnvelope envelope, String payloadSha256) {
		return db.sql("""
				SELECT event_type, aggregate_type, aggregate_id, payload_sha256
				FROM identity_inbox
				WHERE consumer_name = :consumerName AND event_id = :eventId
				""").bind("consumerName", consumerName).bind("eventId", envelope.eventId())
				.map(row -> new ExistingEvent(row.get("event_type", String.class),
						row.get("aggregate_type", String.class), row.get("aggregate_id", String.class),
						row.get("payload_sha256", String.class)))
				.one()
				.switchIfEmpty(
						Mono.error(new EventContractException("source offset already belongs to a different event")))
				.flatMap(existing -> existing.matches(envelope, payloadSha256)
						? Mono.just(false)
						: Mono.error(new EventContractException(
								"conflicting identity event content for eventId " + envelope.eventId())));
	}

	private record ExistingEvent(String eventType, String aggregateType, String aggregateId, String payloadSha256) {

		private boolean matches(IdentityEventEnvelope envelope, String expectedPayloadSha256) {
			return java.util.Objects.equals(eventType, envelope.eventType())
					&& java.util.Objects.equals(aggregateType, envelope.aggregateType())
					&& java.util.Objects.equals(aggregateId, envelope.aggregateId())
					&& java.util.Objects.equals(payloadSha256, expectedPayloadSha256);
		}
	}
}
