package com.grassland.marketplace.workflow.saga;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.ops.OpsCaseRegistrar;
import com.grassland.marketplace.taskcatalog.EngagementSubmission;
import com.grassland.marketplace.taskcatalog.SubmissionRepository;
import com.grassland.marketplace.taskcatalog.SubmissionStatus;
import com.grassland.marketplace.taskcatalog.Task;
import com.grassland.marketplace.taskcatalog.TaskApplication;
import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
import com.grassland.marketplace.workflow.TrustDisputeClient;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** {@link MerchantContestCoordinator} durable claim 恢复、幂等本地完成与 SLA 启窗单测。 */
class MerchantContestCoordinatorTest {

    private TaskApplicationRepository apps;
    private SubmissionRepository submissions;
    private TrustDisputeClient trust;
    private OpsCaseRegistrar opsCases;
    private OutboxRepository outbox;
    private MerchantRejectionReviewWorkflowStarter workflows;
    private MerchantContestCoordinator coordinator;

    private final String appId = UUID.randomUUID().toString();
    private final String taskId = UUID.randomUUID().toString();
    private final String disputeId = UUID.randomUUID().toString();
    private final String orgId = UUID.randomUUID().toString();
    private final Task task = new Task(taskId, "merchant-1", orgId, "title", "desc", "published",
            "video", "douyin", 1, 500L, Instant.now(), Instant.now(), 1, null, null, null);

    @BeforeEach
    void setUp() {
        apps = org.mockito.Mockito.mock(TaskApplicationRepository.class);
        submissions = org.mockito.Mockito.mock(SubmissionRepository.class);
        trust = org.mockito.Mockito.mock(TrustDisputeClient.class);
        opsCases = org.mockito.Mockito.mock(OpsCaseRegistrar.class);
        outbox = org.mockito.Mockito.mock(OutboxRepository.class);
        workflows = org.mockito.Mockito.mock(MerchantRejectionReviewWorkflowStarter.class);
        TransactionalOperator transactions = org.mockito.Mockito.mock(TransactionalOperator.class);
        when(transactions.transactional(any(Mono.class))).thenAnswer(inv -> inv.getArgument(0));
        coordinator = new MerchantContestCoordinator(
                apps, submissions, trust, opsCases, outbox, workflows, transactions, 30L);
    }

    @Test
    void trustFailureLeavesClaimWithoutLocalWrites() {
        TaskApplication claimed = claimed(null, null);
        when(trust.openMerchantRejection(orgId, appId, "merchant-1", "不同意"))
                .thenReturn(Mono.error(new IllegalStateException("trust down")));

        assertThatThrownBy(() -> coordinator.dispatch(claimed, task).block())
                .hasMessage("trust down");

        verify(submissions, never()).findPending(anyString());
        verify(apps, never()).completeContest(anyString(), anyString(), anyString());
        verify(workflows, never()).start(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void premiumClaimPassesAcceptSnapshotToTrust() {
        TaskApplication claimed = premiumClaimed();
        when(trust.openMerchantRejection(orgId, appId, "merchant-1", "不同意", "rec-1", true))
                .thenReturn(Mono.error(new IllegalStateException("stop after contract assertion")));

        assertThatThrownBy(() -> coordinator.dispatch(claimed, task).block())
                .hasMessage("stop after contract assertion");

        verify(trust).openMerchantRejection(orgId, appId, "merchant-1", "不同意", "rec-1", true);
        verify(trust, never()).openMerchantRejection(orgId, appId, "merchant-1", "不同意");
    }

    @Test
    void completesClaimThenStartsAndMarksWorkflow() {
        TaskApplication claimed = claimed(null, null);
        TaskApplication completed = claimed(disputeId, null);
        EngagementSubmission submission = submission(SubmissionStatus.SUBMITTED.dbValue());
        when(trust.openMerchantRejection(orgId, appId, "merchant-1", "不同意"))
                .thenReturn(Mono.just(disputeId));
        when(submissions.findPending(appId)).thenReturn(Mono.just(submission));
        when(submissions.review(submission.id(), SubmissionStatus.ACCEPTED, null))
                .thenReturn(Mono.just(submission(SubmissionStatus.ACCEPTED.dbValue())));
        when(apps.completeContest(appId, taskId, disputeId)).thenReturn(Mono.just(completed));
        when(opsCases.register(any(), eq(disputeId), eq(orgId), eq(appId), eq("不同意")))
                .thenReturn(Mono.empty());
        when(outbox.append(any())).thenReturn(Mono.empty());
        when(apps.findById(appId)).thenReturn(Mono.just(completed));
        when(workflows.start(appId, disputeId, orgId, 30L)).thenReturn(Mono.just("rejection-review-" + appId));
        when(apps.markRejectionWorkflowStarted(appId, disputeId)).thenReturn(Mono.just(true));

        coordinator.dispatch(claimed, task).block();

        verify(trust).openMerchantRejection(orgId, appId, "merchant-1", "不同意");
        verify(outbox).append(any());
        verify(opsCases).register(any(), eq(disputeId), eq(orgId), eq(appId), eq("不同意"));
        verify(workflows).start(appId, disputeId, orgId, 30L);
        verify(apps).markRejectionWorkflowStarted(appId, disputeId);
    }

    @Test
    void completedRetrySkipsTrustAndDomainWritesButRestartsWorkflow() {
        TaskApplication completed = claimed(disputeId, null);
        TaskApplication started = claimed(disputeId, Instant.now());
        when(workflows.start(appId, disputeId, orgId, 30L)).thenReturn(Mono.just("rejection-review-" + appId));
        when(apps.markRejectionWorkflowStarted(appId, disputeId)).thenReturn(Mono.just(true));
        when(apps.findById(appId)).thenReturn(Mono.just(started));

        coordinator.dispatch(completed, task).block();

        verify(trust, never()).openMerchantRejection(anyString(), anyString(), anyString(), any());
        verify(submissions, never()).findPending(anyString());
        verify(outbox, never()).append(any());
        verify(workflows, times(1)).start(appId, disputeId, orgId, 30L);
    }

    @Test
    void workflowFailureDoesNotMarkStarted() {
        TaskApplication completed = claimed(disputeId, null);
        when(workflows.start(appId, disputeId, orgId, 30L))
                .thenReturn(Mono.error(new IllegalStateException("temporal down")));

        assertThatThrownBy(() -> coordinator.dispatch(completed, task).block())
                .hasMessage("temporal down");

        verify(apps, never()).markRejectionWorkflowStarted(anyString(), anyString());
        verify(trust, never()).openMerchantRejection(anyString(), anyString(), anyString(), any());
    }

    private TaskApplication claimed(String existingDisputeId, Instant workflowStartedAt) {
        Instant now = Instant.now();
        return new TaskApplication(appId, taskId, "rec-1", "accepted", null, "merchant-1",
                null, null, null, existingDisputeId == null ? null : now, 500L, now.plusSeconds(60), null,
                existingDisputeId == null ? null : now, "不同意", existingDisputeId, now, workflowStartedAt);
    }

    private TaskApplication premiumClaimed() {
        Instant now = Instant.now();
        return new TaskApplication(appId, taskId, "rec-1", "accepted", null, "merchant-1",
                null, null, null, null, 500L, now.plusSeconds(60), null,
                null, "不同意", null, now, null, 4, 1L, 2, 500, true);
    }

    private EngagementSubmission submission(String status) {
        return new EngagementSubmission(UUID.randomUUID().toString(), appId, "rec-1",
                "https://example.com/post", null, status, null, null, Instant.now(), null);
    }
}
