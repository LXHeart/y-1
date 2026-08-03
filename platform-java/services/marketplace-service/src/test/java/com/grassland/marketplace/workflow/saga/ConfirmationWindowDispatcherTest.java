package com.grassland.marketplace.workflow.saga;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.taskcatalog.EngagementSubmission;
import com.grassland.marketplace.taskcatalog.SubmissionRepository;
import com.grassland.marketplace.taskcatalog.Task;
import com.grassland.marketplace.taskcatalog.TaskApplication;
import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
import com.grassland.marketplace.taskcatalog.TaskRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@link ConfirmationWindowDispatcher} 单测（D-03）：空批不动；成功路径标记；start 失败不标记留待下轮；
 * deadline 已过期用 0 秒；已确认/deadline 缺失跳过。
 */
class ConfirmationWindowDispatcherTest {

    private SubmissionRepository submissions;
    private TaskApplicationRepository apps;
    private TaskRepository tasks;
    private ConfirmationWorkflowStarter starter;
    private ConfirmationWindowDispatcher dispatcher;

    private final String submissionId = UUID.randomUUID().toString();
    private final String appId = UUID.randomUUID().toString();
    private final String taskId = UUID.randomUUID().toString();
    private final String orgId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        submissions = org.mockito.Mockito.mock(SubmissionRepository.class);
        apps = org.mockito.Mockito.mock(TaskApplicationRepository.class);
        tasks = org.mockito.Mockito.mock(TaskRepository.class);
        starter = org.mockito.Mockito.mock(ConfirmationWorkflowStarter.class);
        dispatcher = new ConfirmationWindowDispatcher(submissions, apps, tasks, starter, 32, 86400L);
    }

    @Test
    void emptyBatchDoesNothing() {
        when(submissions.findConfirmationDispatchable(32)).thenReturn(Flux.empty());

        dispatcher.dispatchBatch();

        verify(starter, never()).start(anyString(), anyString(), anyString(), anyLong(), anyLong());
        verify(submissions, never()).markConfirmationWorkflowStarted(anyString());
    }

    @Test
    void startsWithRemainingSecondsAndMarks() {
        EngagementSubmission submission = submission();
        TaskApplication app = app(Instant.now().plusSeconds(120), null);
        when(submissions.findConfirmationDispatchable(32)).thenReturn(Flux.just(submission));
        when(apps.findById(appId)).thenReturn(Mono.just(app));
        when(tasks.findById(taskId)).thenReturn(Mono.just(task()));
        when(starter.start(eq(appId), eq(submissionId), eq(orgId), org.mockito.ArgumentMatchers.longThat(s -> s > 0 && s <= 120), anyLong()))
                .thenReturn(Mono.just("confirm-" + submissionId));
        when(submissions.markConfirmationWorkflowStarted(submissionId)).thenReturn(Mono.just(true));

        dispatcher.dispatchBatch();

        verify(submissions).markConfirmationWorkflowStarted(submissionId);
    }

    @Test
    void expiredDeadlineStartsWithZeroSeconds() {
        EngagementSubmission submission = submission();
        TaskApplication app = app(Instant.now().minusSeconds(10), null);
        when(submissions.findConfirmationDispatchable(32)).thenReturn(Flux.just(submission));
        when(apps.findById(appId)).thenReturn(Mono.just(app));
        when(tasks.findById(taskId)).thenReturn(Mono.just(task()));
        when(starter.start(eq(appId), eq(submissionId), eq(orgId), eq(0L), anyLong()))
                .thenReturn(Mono.just("confirm-" + submissionId));
        when(submissions.markConfirmationWorkflowStarted(submissionId)).thenReturn(Mono.just(true));

        dispatcher.dispatchBatch();

        verify(starter).start(appId, submissionId, orgId, 0L, 0L);
        verify(submissions).markConfirmationWorkflowStarted(submissionId);
    }

    @Test
    void startFailureDoesNotMarkForRetry() {
        EngagementSubmission submission = submission();
        TaskApplication app = app(Instant.now().plusSeconds(120), null);
        when(submissions.findConfirmationDispatchable(32)).thenReturn(Flux.just(submission));
        when(apps.findById(appId)).thenReturn(Mono.just(app));
        when(tasks.findById(taskId)).thenReturn(Mono.just(task()));
        when(starter.start(eq(appId), eq(submissionId), eq(orgId), anyLong(), anyLong()))
                .thenReturn(Mono.error(new IllegalStateException("temporal unavailable")));

        dispatcher.dispatchBatch();

        verify(submissions, never()).markConfirmationWorkflowStarted(anyString());
    }

    @Test
    void alreadyConfirmedSkips() {
        EngagementSubmission submission = submission();
        TaskApplication app = app(Instant.now().plusSeconds(120), Instant.now());
        when(submissions.findConfirmationDispatchable(32)).thenReturn(Flux.just(submission));
        when(apps.findById(appId)).thenReturn(Mono.just(app));

        dispatcher.dispatchBatch();

        verify(starter, never()).start(anyString(), anyString(), anyString(), anyLong(), anyLong());
        verify(submissions, never()).markConfirmationWorkflowStarted(anyString());
    }

    @Test
    void multipleRowsDispatched() {
        EngagementSubmission s1 = submission();
        EngagementSubmission s2 = new EngagementSubmission(
                UUID.randomUUID().toString(), appId, "rec-2", "https://example.com/b", null,
                "submitted", null, null, Instant.now(), null);
        TaskApplication app = app(Instant.now().plusSeconds(60), null);
        when(submissions.findConfirmationDispatchable(32)).thenReturn(Flux.fromIterable(List.of(s1, s2)));
        when(apps.findById(appId)).thenReturn(Mono.just(app));
        when(tasks.findById(taskId)).thenReturn(Mono.just(task()));
        when(starter.start(eq(appId), anyString(), eq(orgId), anyLong(), anyLong())).thenReturn(Mono.just("confirm-x"));
        when(submissions.markConfirmationWorkflowStarted(anyString())).thenReturn(Mono.just(true));

        dispatcher.dispatchBatch();

        verify(submissions).markConfirmationWorkflowStarted(s1.id());
        verify(submissions).markConfirmationWorkflowStarted(s2.id());
    }

    private EngagementSubmission submission() {
        return new EngagementSubmission(
                submissionId, appId, "rec-1", "https://example.com/a", null,
                "submitted", null, null, Instant.now(), null);
    }

    private TaskApplication app(Instant deadline, Instant confirmedAt) {
        return new TaskApplication(appId, taskId, "rec-1", "accepted", null, "merchant-1",
                null, null, null, confirmedAt, 500L, deadline, null);
    }

    private Task task() {
        // 只需 organizationId；其余字段用最小可构造值。
        return new Task(taskId, "merchant-1", orgId, "title", "desc", "published", "video", "douyin",
                1, 500L, Instant.now(), Instant.now(), 1, null, null, null);
    }
}
