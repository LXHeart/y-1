package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class TaskReviewSlaDispatcherTest {
    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final TaskResourceAuthorization authorization = mock(TaskResourceAuthorization.class);
    private final TaskPublishGate publishGate = mock(TaskPublishGate.class);
    private final TaskReviewService reviews = mock(TaskReviewService.class);
    private final TransactionalOperator transactions = mock(TransactionalOperator.class);

    @Test
    void overdueTaskRevalidatesOwnerAndQuotaBeforeSystemApproval() {
        Task task = task("pending_review", 2);
        Task approved = task("published", 3);
        when(tasks.findPendingReviewBefore(NOW.minus(Duration.ofHours(24)), 20)).thenReturn(Flux.just(task));
        when(authorization.requireCurrentOwnerManager(task))
                .thenReturn(Mono.just(new TaskResourceAuthorization.ManagedTask(task, "finance_transaction")));
        when(tasks.acquireOrganizationPublishLock("org-1")).thenReturn(Mono.empty());
        when(publishGate.enforce("org-1", "finance_transaction", 500L)).thenReturn(Mono.empty());
        when(reviews.approveSystem(task, "sla_timeout", "review SLA exceeded after 24h"))
                .thenReturn(Mono.just(approved));
        when(transactions.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));
        TaskReviewSlaDispatcher dispatcher = dispatcher(true);

        assertThat(dispatcher.processBatch().block()).isEqualTo(1);
        verify(publishGate).enforce("org-1", "finance_transaction", 500L);
        verify(reviews).approveSystem(task, "sla_timeout", "review SLA exceeded after 24h");
    }

    @Test
    void disabledDispatcherDoesNotReadQueue() {
        assertThat(dispatcher(false).processBatch().block()).isZero();
        verifyNoInteractions(tasks, authorization, publishGate, reviews, transactions);
    }

    private TaskReviewSlaDispatcher dispatcher(boolean enabled) {
        return new TaskReviewSlaDispatcher(tasks, authorization, publishGate, reviews, transactions,
                Duration.ofHours(24), 20, enabled, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Task task(String status, int version) {
        return new Task("task-1", "merchant-1", "org-1", "title", "description", status,
                "video", "douyin", 1, 500L, NOW.minusSeconds(100_000), NOW.minusSeconds(90_000),
                version, null, "published".equals(status) ? NOW : null, null);
    }
}
