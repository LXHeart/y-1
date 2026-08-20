package com.grassland.messaging.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * outbox → Kafka 可靠发布器：claim → send（等 ack）→ markPublished，失败按位移指数退避。
 *
 * <p>
 * 纯类（无 Spring 注解）：调度属性名 {@code <svc>.outbox.poll-interval-ms} 是各服务 私有配置面，由服务侧
 * {@code OutboxMessagingConfig} 的调度 bean 以 {@code @Scheduled(fixedDelayString =
 * "${<svc>.outbox.poll-interval-ms:2000}")} 驱动
 * {@link #publishPending()}。{@code kafka == null}（无 Kafka 环境，如测试）时静默跳过， 与原实现一致。
 *
 * <p>
 * 指标名跨服务共享（{@code grassland.outbox.*}）——每服务独立 MeterRegistry/端点， 无冲突；告警规则按服务 job
 * 维度区分。原四份逐字相同的拷贝 2026-08-20 下沉到本库。
 */
public class OutboxPublisher {

	private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
	private static final int MAX_ERROR_CODE_LENGTH = 64;

	private final OutboxRepository repository;
	private final KafkaTemplate<String, String> kafka;
	private final ObjectMapper mapper;
	private final OutboxSettings settings;
	private final String owner;
	private final AtomicBoolean isPublishing = new AtomicBoolean();
	private final AtomicLong pendingGauge = new AtomicLong();
	private final AtomicLong oldestPendingAgeGauge = new AtomicLong();
	private final Counter attemptsCounter;
	private final Counter successCounter;
	private final Counter failuresCounter;
	private final Counter markFailuresCounter;
	private final Counter overlapCounter;
	private final Timer publishDuration;

	public OutboxPublisher(OutboxRepository repository, KafkaTemplate<String, String> kafka, OutboxSettings settings,
			String owner, MeterRegistry meterRegistry) {
		this(repository, kafka, new ObjectMapper().findAndRegisterModules(), meterRegistry, settings, owner);
	}

	OutboxPublisher(OutboxRepository repository, KafkaTemplate<String, String> kafka, ObjectMapper mapper,
			MeterRegistry meterRegistry, OutboxSettings settings, String owner) {
		this.repository = repository;
		this.kafka = kafka;
		this.mapper = mapper.copy().findAndRegisterModules();
		this.settings = settings;
		this.owner = owner;
		attemptsCounter = Counter.builder("grassland.outbox.publish.attempts").register(meterRegistry);
		successCounter = Counter.builder("grassland.outbox.publish.success").register(meterRegistry);
		failuresCounter = Counter.builder("grassland.outbox.publish.failures").register(meterRegistry);
		markFailuresCounter = Counter.builder("grassland.outbox.mark.failures").register(meterRegistry);
		overlapCounter = Counter.builder("grassland.outbox.poll.overlap").register(meterRegistry);
		publishDuration = Timer.builder("grassland.outbox.publish.duration").register(meterRegistry);
		Gauge.builder("grassland.outbox.pending", pendingGauge, AtomicLong::get).register(meterRegistry);
		Gauge.builder("grassland.outbox.oldest.pending.age", oldestPendingAgeGauge, AtomicLong::get).baseUnit("seconds")
				.register(meterRegistry);
	}

	public void publishPending() {
		if (!settings.enabled() || kafka == null) {
			return;
		}
		if (!isPublishing.compareAndSet(false, true)) {
			overlapCounter.increment();
			return;
		}

		UUID claimToken = UUID.randomUUID();
		repository.claimBatch(settings.batchSize(), claimToken, settings.claimLease())
				.flatMap(this::publishClaimed, settings.maxConcurrency()).then()
				.doOnError(error -> log.error("Failed to process {} outbox batch", owner, error))
				.onErrorResume(error -> Mono.empty()).then(refreshBacklogMetrics())
				.doFinally(signal -> isPublishing.set(false)).subscribe();
	}

	private Mono<Void> publishClaimed(OutboxRepository.OutboxRow row) {
		long startedAt = System.nanoTime();
		attemptsCounter.increment();
		return Mono.fromCallable(() -> buildEnvelope(row))
				.flatMap(message -> Mono.fromCallable(() -> kafka.send(settings.topic(), row.aggregateId(), message))
						.subscribeOn(Schedulers.boundedElastic()).flatMap(Mono::fromFuture)
						.timeout(settings.ackTimeout()))
				.then(markPublished(row)).onErrorResume(error -> markFailure(row, error))
				.doFinally(signal -> publishDuration.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS));
	}

	private Mono<Void> markPublished(OutboxRepository.OutboxRow row) {
		return Mono.defer(() -> repository.markPublished(row.id(), row.claimToken())).flatMap(updated -> {
			if (updated) {
				successCounter.increment();
			} else {
				markFailuresCounter.increment();
			}
			return Mono.<Void>empty();
		}).onErrorResume(error -> {
			markFailuresCounter.increment();
			log.warn("Failed to mark {} outbox event published: eventId={}", owner, row.eventId());
			return Mono.<Void>empty();
		});
	}

	private Mono<Void> markFailure(OutboxRepository.OutboxRow row, Throwable error) {
		failuresCounter.increment();
		String errorCode = errorCode(error);
		Duration retryDelay = Duration.ofMillis(backoffMillis(row.attemptCount()));
		return Mono.defer(() -> repository.markFailure(row.id(), row.claimToken(), retryDelay, errorCode))
				.flatMap(updated -> {
					if (!updated) {
						markFailuresCounter.increment();
					}
					return Mono.<Void>empty();
				}).onErrorResume(markError -> {
					markFailuresCounter.increment();
					log.warn("Failed to mark {} outbox retry: eventId={}, errorCode={}", owner, row.eventId(),
							errorCode);
					return Mono.<Void>empty();
				});
	}

	private Mono<Void> refreshBacklogMetrics() {
		return Mono.zip(repository.pendingCount().defaultIfEmpty(0L),
				repository.oldestPendingAgeSeconds().defaultIfEmpty(0L)).doOnNext(backlog -> {
					pendingGauge.set(backlog.getT1());
					oldestPendingAgeGauge.set(backlog.getT2());
				}).doOnError(error -> log.warn("Failed to refresh {} outbox metrics", owner))
				.onErrorResume(error -> Mono.empty()).then();
	}

	private String buildEnvelope(OutboxRepository.OutboxRow row) throws Exception {
		JsonNode payload = mapper.readTree(row.payloadJson());
		return mapper.writeValueAsString(new KafkaEventEnvelope(row.eventId(), row.eventType(), row.aggregateType(),
				row.aggregateId(), payload));
	}

	private long backoffMillis(int attemptCount) {
		int exponent = Math.max(0, Math.min(attemptCount - 1, 62));
		long multiplier = 1L << exponent;
		long initial = settings.initialBackoffMs();
		long maximum = settings.maxBackoffMs();
		if (multiplier > maximum / initial) {
			return maximum;
		}
		return Math.min(initial * multiplier, maximum);
	}

	private static String errorCode(Throwable error) {
		String code = Exceptions.unwrap(error).getClass().getSimpleName();
		return code.length() <= MAX_ERROR_CODE_LENGTH ? code : code.substring(0, MAX_ERROR_CODE_LENGTH);
	}

	private record KafkaEventEnvelope(String eventId, String eventType, String aggregateType, String aggregateId,
			JsonNode payload) {
	}
}
