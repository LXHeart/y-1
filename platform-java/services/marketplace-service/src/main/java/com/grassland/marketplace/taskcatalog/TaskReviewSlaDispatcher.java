package com.grassland.marketplace.taskcatalog;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** Automatically approves review items after SLA while re-running current tier and quota gates. */
@Component
public class TaskReviewSlaDispatcher {
    private static final Logger log = LoggerFactory.getLogger(TaskReviewSlaDispatcher.class);

    private final TaskRepository tasks;
    private final TaskResourceAuthorization authorization;
    private final TaskPublishGate publishGate;
    private final TaskReviewService reviews;
    private final TransactionalOperator transactions;
    private final Duration sla;
    private final int batchSize;
    private final boolean enabled;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public TaskReviewSlaDispatcher(
            TaskRepository tasks, TaskResourceAuthorization authorization, TaskPublishGate publishGate,
            TaskReviewService reviews, TransactionalOperator transactions,
            @Value("${marketplace.task-review.sla-hours:24}") long slaHours,
            @Value("${marketplace.task-review.batch-size:50}") int batchSize,
            @Value("${marketplace.task-review.sla-auto-approve-enabled:true}") boolean enabled) {
        this(tasks, authorization, publishGate, reviews, transactions, Duration.ofHours(Math.max(1, slaHours)),
                batchSize, enabled, Clock.systemUTC());
    }

    TaskReviewSlaDispatcher(TaskRepository tasks, TaskResourceAuthorization authorization,
                            TaskPublishGate publishGate, TaskReviewService reviews,
                            TransactionalOperator transactions, Duration sla, int batchSize,
                            boolean enabled, Clock clock) {
        this.tasks = tasks;
        this.authorization = authorization;
        this.publishGate = publishGate;
        this.reviews = reviews;
        this.transactions = transactions;
        this.sla = sla;
        this.batchSize = Math.max(1, Math.min(batchSize, 200));
        this.enabled = enabled;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${marketplace.task-review.poll-ms:60000}")
    public void dispatch() {
        processBatch().subscribe(
                count -> { if (count > 0) log.info("task review SLA auto-approved {} item(s)", count); },
                error -> log.error("task review SLA dispatcher failed", error));
    }

    Mono<Integer> processBatch() {
        if (!enabled) return Mono.just(0);
        return tasks.findPendingReviewBefore(clock.instant().minus(sla), batchSize)
                .concatMap(task -> authorization.requireCurrentOwnerManager(task)
                        .flatMap(access -> transactions.transactional(
                                tasks.acquireOrganizationPublishLock(task.organizationId())
                                        .then(publishGate.enforce(task.organizationId(), access.permissionTier(),
                                                task.bountyCents()))
                                        .then(reviews.approveSystem(task, "sla_timeout",
                                                "review SLA exceeded after " + sla.toHours() + "h"))))
                        .thenReturn(1)
                        .onErrorResume(error -> {
                            log.warn("task review SLA item retained taskId={} reason={}", task.id(), error.getMessage());
                            return Mono.just(0);
                        }))
                .reduce(0, Integer::sum);
    }
}
