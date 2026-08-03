package com.grassland.marketplace.workflow.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.taskcatalog.EngagementSubmission;
import com.grassland.marketplace.taskcatalog.SubmissionRepository;
import com.grassland.marketplace.taskcatalog.SubmissionStatus;
import com.grassland.marketplace.taskcatalog.TaskApplication;
import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
import com.grassland.marketplace.taskcatalog.TaskRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * {@link ConfirmationActivityImpl} 单测（D-03）：submission 绑定、自动确认原子写、手动确认竞态、
 * activity 崩溃重试、AutoSettledOnTimeout exactly-once 与 captureOrHold 映射。
 */
class ConfirmationActivityImplTest {

    private TaskApplicationRepository apps;
    private TaskRepository tasks;
    private SubmissionRepository submissions;
    private OutboxRepository outbox;
    private SettlementExecution settlementExecution;
    private TransactionalOperator transactions;
    private ConfirmationActivityImpl activity;

    private final String appId = UUID.randomUUID().toString();
    private final String taskId = UUID.randomUUID().toString();
    private final String submissionId = UUID.randomUUID().toString();
    private final String orgId = UUID.randomUUID().toString();
    private final ConfirmationInput input = new ConfirmationInput(appId, submissionId, orgId, 0L, 0L);

    @BeforeEach
    void setUp() {
        apps = org.mockito.Mockito.mock(TaskApplicationRepository.class);
        tasks = org.mockito.Mockito.mock(TaskRepository.class);
        submissions = org.mockito.Mockito.mock(SubmissionRepository.class);
        outbox = org.mockito.Mockito.mock(OutboxRepository.class);
        settlementExecution = org.mockito.Mockito.mock(SettlementExecution.class);
        transactions = org.mockito.Mockito.mock(TransactionalOperator.class);
        when(transactions.transactional(any(Mono.class))).thenAnswer(inv -> inv.getArgument(0));
        when(outbox.append(any())).thenReturn(Mono.empty());
        when(tasks.findById(taskId)).thenReturn(Mono.empty());  // 任务缺失 → taskOwnerId=null，不阻断结算
        activity = new ConfirmationActivityImpl(
                apps, tasks, submissions, outbox, settlementExecution, transactions);
    }

    @Test
    void abortsWhenApplicationMissing() {
        when(apps.findById(appId)).thenReturn(Mono.empty());

        ConfirmationOutcome result = activity.autoConfirmSettle(input);

        assertThat(result.status()).isEqualTo("aborted");
        verify(settlementExecution, never()).captureOrHold(anyString(), anyString(), any(), any());
    }

    @Test
    void abortsWhenNotAccepted() {
        when(apps.findById(appId)).thenReturn(Mono.just(app("withdrawn", null, null)));

        ConfirmationOutcome result = activity.autoConfirmSettle(input);

        assertThat(result.status()).isEqualTo("aborted");
        verify(submissions, never()).findById(anyString());
    }

    @Test
    void merchantConfirmedFirstAbortsWithoutCapturing() {
        // confirmed_at 有值、auto_confirmed_at 无值 = 商家手动确认先赢；由其 SettlementWindow 接管。
        when(apps.findById(appId)).thenReturn(Mono.just(app("accepted", Instant.now(), null)));

        ConfirmationOutcome result = activity.autoConfirmSettle(input);

        assertThat(result.status()).isEqualTo("aborted");
        verify(apps, never()).autoConfirm(anyString(), anyString());
        verify(settlementExecution, never()).captureOrHold(anyString(), anyString(), any(), any());
    }

    @Test
    void contestClaimedFirstHoldsWithoutAutoConfirmOrCapture() {
        TaskApplication claimed = new TaskApplication(
                appId, taskId, "rec-1", "accepted", null, "merchant-1",
                null, null, null, null, 500L, null, null,
                null, "不同意", null, Instant.now(), null);
        when(apps.findById(appId)).thenReturn(Mono.just(claimed));

        ConfirmationOutcome result = activity.autoConfirmSettle(input);

        assertThat(result.status()).isEqualTo("held");
        assertThat(result.reason()).isEqualTo("merchant_contest_requested");
        verify(apps, never()).autoConfirm(anyString(), anyString());
        verify(settlementExecution, never()).captureOrHold(anyString(), anyString(), any(), any());
    }

    @Test
    void firstAutoConfirmAcceptsBoundSubmissionEmitsEventThenCaptures() {
        TaskApplication pending = app("accepted", null, null);
        TaskApplication autoConfirmed = app("accepted", Instant.now(), Instant.now());
        EngagementSubmission submitted = submission(SubmissionStatus.SUBMITTED.dbValue());
        when(apps.findById(appId)).thenReturn(Mono.just(pending));
        when(submissions.findById(submissionId)).thenReturn(Mono.just(submitted));
        when(submissions.review(submissionId, SubmissionStatus.ACCEPTED, null))
                .thenReturn(Mono.just(submission(SubmissionStatus.ACCEPTED.dbValue())));
        when(apps.autoConfirm(appId, taskId)).thenReturn(Mono.just(autoConfirmed));
        when(settlementExecution.captureOrHold(eq(orgId), eq(appId), any(), any()))
                .thenReturn(SettlementOutcome.settled());

        ConfirmationOutcome result = activity.autoConfirmSettle(input);

        assertThat(result.status()).isEqualTo("auto_settled");
        verify(outbox).append(argThat(event -> event != null
                && "AutoSettledOnTimeout".equals(event.eventType())
                && submissionId.equals(event.payload().get("submissionId"))));
        verify(settlementExecution).captureOrHold(eq(orgId), eq(appId), any(), any());
    }

    @Test
    void oldWindowAbortsAfterSubmissionRejected() {
        when(apps.findById(appId)).thenReturn(Mono.just(app("accepted", null, null)));
        when(submissions.findById(submissionId))
                .thenReturn(Mono.just(submission(SubmissionStatus.REJECTED.dbValue())));

        ConfirmationOutcome result = activity.autoConfirmSettle(input);

        assertThat(result.status()).isEqualTo("aborted");
        verify(apps, never()).autoConfirm(anyString(), anyString());
        verify(settlementExecution, never()).captureOrHold(anyString(), anyString(), any(), any());
    }

    @Test
    void autoConfirmedRetryDoesNotReEmitButStillCaptures() {
        // 首次 activity 已写 auto_confirmed_at 后崩溃；重试跳过领域写/outbox，继续幂等 capture。
        TaskApplication autoConfirmed = app("accepted", Instant.now(), Instant.now());
        when(apps.findById(appId)).thenReturn(Mono.just(autoConfirmed));
        when(settlementExecution.captureOrHold(eq(orgId), eq(appId), any(), any()))
                .thenReturn(SettlementOutcome.settled());

        ConfirmationOutcome result = activity.autoConfirmSettle(input);

        assertThat(result.status()).isEqualTo("auto_settled");
        verify(submissions, never()).findById(anyString());
        verify(outbox, never()).append(any());
        verify(settlementExecution).captureOrHold(eq(orgId), eq(appId), any(), any());
    }

    @Test
    void captureHeldMapsToHeld() {
        TaskApplication autoConfirmed = app("accepted", Instant.now(), Instant.now());
        when(apps.findById(appId)).thenReturn(Mono.just(autoConfirmed));
        when(settlementExecution.captureOrHold(eq(orgId), eq(appId), any(), any()))
                .thenReturn(SettlementOutcome.held("open_dispute"));

        ConfirmationOutcome result = activity.autoConfirmSettle(input);

        assertThat(result.status()).isEqualTo("held");
        assertThat(result.reason()).isEqualTo("open_dispute");
    }

    // ---------- notifyExpiring（slice 2 临到期提醒）----------

    @Test
    void notifyExpiringEmitsEventWhenAwaitingConfirmation() {
        when(apps.findById(appId)).thenReturn(Mono.just(app("accepted", null, null)));
        when(submissions.findById(submissionId)).thenReturn(Mono.just(submission(SubmissionStatus.SUBMITTED.dbValue())));

        activity.notifyExpiring(input);

        verify(outbox).append(argThat(event -> event != null
                && "ConfirmationWindowExpiring".equals(event.eventType())
                && submissionId.equals(event.payload().get("submissionId"))));
    }

    @Test
    void notifyExpiringSkipsWhenAlreadyConfirmed() {
        when(apps.findById(appId)).thenReturn(Mono.just(app("accepted", Instant.now(), null)));

        activity.notifyExpiring(input);

        verify(outbox, never()).append(any());
        verify(submissions, never()).findById(anyString());
    }

    @Test
    void notifyExpiringSkipsWhenSubmissionRejected() {
        when(apps.findById(appId)).thenReturn(Mono.just(app("accepted", null, null)));
        when(submissions.findById(submissionId))
                .thenReturn(Mono.just(submission(SubmissionStatus.REJECTED.dbValue())));

        activity.notifyExpiring(input);

        verify(outbox, never()).append(any());
    }

    private TaskApplication app(String status, Instant confirmedAt, Instant autoConfirmedAt) {
        return new TaskApplication(appId, taskId, "rec-1", status, null, "merchant-1",
                null, null, null, confirmedAt, 500L, null, autoConfirmedAt);
    }

    private EngagementSubmission submission(String status) {
        return new EngagementSubmission(
                submissionId, appId, "rec-1", "https://example.com/post", null,
                status, null, null, Instant.now(), null);
    }
}
