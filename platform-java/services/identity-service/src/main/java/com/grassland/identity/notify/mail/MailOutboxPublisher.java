package com.grassland.identity.notify.mail;

import com.grassland.identity.notify.SmtpMailSender;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 事务邮件 outbox 派发器（GL-P1-NOTIFY-001）。镜像 {@code event/OutboxPublisher}（identity 领域 outbox），
 * 差异：
 * <ul>
 *   <li>外部 send 是 {@link SmtpMailSender#send}（阻塞 SMTP）而非 {@code KafkaTemplate.send}；</li>
 *   <li>失败时 {@code attempt_count >= maxAttempts} → {@link MailOutboxRepository#markDead}（死信封顶），
 *       否则 {@link MailOutboxRepository#markFailure}（指数退避）。领域 outbox 是无限重试，邮件不可如此。</li>
 * </ul>
 *
 * <p>轮询节奏、overlap guard、claim→send→mark、backlog metrics 与领域 outbox 同构。
 * 未启用（{@code enabled=false}）或 SMTP 未配（{@code isConfigured()=false}）时跳过发送，
 * 但仍刷新 pending/dead backlog gauges，避免停发或重启期间历史死信被误报为 0。
 */
@Component
public class MailOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(MailOutboxPublisher.class);
    private static final int MAX_ERROR_CODE_LENGTH = 64;

    private final MailOutboxRepository repository;
    private final SmtpMailSender mailSender;
    private final MailOutboxProperties properties;
    private final AtomicBoolean isPublishing = new AtomicBoolean();
    private final AtomicLong pendingGauge = new AtomicLong();
    private final AtomicLong deadGauge = new AtomicLong();
    private final Counter attemptsCounter;
    private final Counter successCounter;
    private final Counter failuresCounter;
    private final Counter deadCounter;
    private final Counter markFailuresCounter;
    private final Counter overlapCounter;
    private final Timer publishDuration;

    public MailOutboxPublisher(
            MailOutboxRepository repository,
            SmtpMailSender mailSender,
            MeterRegistry meterRegistry,
            MailOutboxProperties properties) {
        this.repository = repository;
        this.mailSender = mailSender;
        this.properties = properties;
        attemptsCounter = Counter.builder("grassland.mail.outbox.attempts").register(meterRegistry);
        successCounter = Counter.builder("grassland.mail.outbox.success").register(meterRegistry);
        failuresCounter = Counter.builder("grassland.mail.outbox.failures").register(meterRegistry);
        deadCounter = Counter.builder("grassland.mail.outbox.dead").register(meterRegistry);
        markFailuresCounter = Counter.builder("grassland.mail.outbox.mark.failures").register(meterRegistry);
        overlapCounter = Counter.builder("grassland.mail.outbox.poll.overlap").register(meterRegistry);
        publishDuration = Timer.builder("grassland.mail.outbox.publish.duration").register(meterRegistry);
        Gauge.builder("grassland.mail.outbox.pending", pendingGauge, AtomicLong::get).register(meterRegistry);
        Gauge.builder("grassland.mail.outbox.dead.current", deadGauge, AtomicLong::get).register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${identity.mail-outbox.poll-interval-ms:2000}")
    public void publishPending() {
        if (!isPublishing.compareAndSet(false, true)) {
            overlapCounter.increment();
            return;
        }

        Mono<Void> delivery = Mono.empty();
        if (properties.enabled() && mailSender.isConfigured()) {
            UUID claimToken = UUID.randomUUID();
            delivery = repository.claimBatch(properties.batchSize(), claimToken, properties.claimLease())
                    .flatMap(this::publishClaimed, properties.maxConcurrency())
                    .then();
        }
        delivery
                .doOnError(error -> log.error("Failed to process mail outbox batch", error))
                .onErrorResume(error -> Mono.empty())
                .then(refreshBacklogMetrics())
                .doFinally(signal -> isPublishing.set(false))
                .subscribe();
    }

    private Mono<Void> publishClaimed(MailOutboxRepository.MailOutboxRow row) {
        long startedAt = System.nanoTime();
        attemptsCounter.increment();
        return Mono.fromCallable(() -> {
                    mailSender.send(row.recipient(), row.subject(), row.body());
                    return (Void) null;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(properties.sendTimeout())
                .then(markSent(row))
                .onErrorResume(error -> handleFailure(row, error))
                .doFinally(signal -> publishDuration.record(
                        System.nanoTime() - startedAt, TimeUnit.NANOSECONDS));
    }

    private Mono<Void> markSent(MailOutboxRepository.MailOutboxRow row) {
        return Mono.defer(() -> repository.markSent(row.id(), row.claimToken()))
                .flatMap(updated -> {
                    if (updated) {
                        successCounter.increment();
                    } else {
                        markFailuresCounter.increment();
                    }
                    return Mono.<Void>empty();
                })
                .onErrorResume(error -> {
                    markFailuresCounter.increment();
                    log.warn("Failed to mark mail sent: recipient={}", row.recipient());
                    return Mono.<Void>empty();
                });
    }

    /**
     * 发送失败：{@code attempt_count >= maxAttempts} → 死信（停止重试 + 告警）；否则退避重试。
     *
     * <p>{@code row.attemptCount()} 是 claim 时已 +1 的值（本次尝试序号）。maxAttempts=5 时，
     * 第 5 次失败（attemptCount=5）即置死信——最多尝试 5 次。
     */
    private Mono<Void> handleFailure(MailOutboxRepository.MailOutboxRow row, Throwable error) {
        failuresCounter.increment();
        String errorCode = errorCode(error);
        if (row.attemptCount() >= properties.maxAttempts()) {
            return Mono.defer(() -> repository.markDead(row.id(), row.claimToken(), errorCode))
                    .flatMap(updated -> {
                        if (updated) {
                            deadCounter.increment();
                            log.warn("Mail dead-lettered after {} attempts: recipient={}, errorCode={}",
                                    row.attemptCount(), row.recipient(), errorCode);
                        } else {
                            markFailuresCounter.increment();
                        }
                        return Mono.<Void>empty();
                    })
                    .onErrorResume(markError -> {
                        markFailuresCounter.increment();
                        log.warn("Failed to mark mail dead: recipient={}", row.recipient());
                        return Mono.<Void>empty();
                    });
        }
        Duration retryDelay = Duration.ofMillis(backoffMillis(row.attemptCount()));
        return Mono.defer(() -> repository.markFailure(row.id(), row.claimToken(), retryDelay, errorCode))
                .flatMap(updated -> {
                    if (!updated) {
                        markFailuresCounter.increment();
                    }
                    return Mono.<Void>empty();
                })
                .onErrorResume(markError -> {
                    markFailuresCounter.increment();
                    log.warn("Failed to mark mail retry: recipient={}, errorCode={}", row.recipient(), errorCode);
                    return Mono.<Void>empty();
                });
    }

    private Mono<Void> refreshBacklogMetrics() {
        Mono<Void> pending = repository.pendingCount().defaultIfEmpty(0L)
                .doOnNext(pendingGauge::set)
                .doOnError(error -> log.warn("Failed to refresh mail outbox pending metric"))
                .onErrorResume(error -> Mono.empty())
                .then();
        Mono<Void> dead = repository.deadCount().defaultIfEmpty(0L)
                .doOnNext(deadGauge::set)
                .doOnError(error -> log.warn("Failed to refresh mail outbox dead metric"))
                .onErrorResume(error -> Mono.empty())
                .then();
        return Mono.when(pending, dead);
    }

    private long backoffMillis(int attemptCount) {
        int exponent = Math.max(0, Math.min(attemptCount - 1, 62));
        long multiplier = 1L << exponent;
        long initial = properties.initialBackoffMs();
        long maximum = properties.maxBackoffMs();
        if (multiplier > maximum / initial) {
            return maximum;
        }
        return Math.min(initial * multiplier, maximum);
    }

    private static String errorCode(Throwable error) {
        String code = Exceptions.unwrap(error).getClass().getSimpleName();
        return code.length() <= MAX_ERROR_CODE_LENGTH
                ? code
                : code.substring(0, MAX_ERROR_CODE_LENGTH);
    }
}
