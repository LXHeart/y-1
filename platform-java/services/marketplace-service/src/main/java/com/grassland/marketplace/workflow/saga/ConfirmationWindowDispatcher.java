package com.grassland.marketplace.workflow.saga;

import com.grassland.marketplace.taskcatalog.EngagementSubmission;
import com.grassland.marketplace.taskcatalog.SubmissionRepository;
import com.grassland.marketplace.taskcatalog.TaskApplication;
import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
import com.grassland.marketplace.taskcatalog.TaskRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * D-03 确认窗口补启派发器：消除「DB 提交成功、Temporal start 前进程崩溃/网络失败」的提交间隙。
 *
 * <p>submission 是 durable intent：{@code status=submitted AND confirmation_workflow_started_at IS NULL} 即待派发。
 * 按 DB {@code merchant_confirm_deadline_at} 计算**剩余秒数**（而非重置完整窗口），确定性 workflowId
 * {@code confirm-<submissionId>} + AlreadyStarted 幂等，多实例/重复扫描安全。启动成功后 guarded 标记。
 */
@Component
@ConditionalOnProperty(prefix = "marketplace.confirmation", name = "dispatcher-enabled",
        havingValue = "true", matchIfMissing = true)
public class ConfirmationWindowDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationWindowDispatcher.class);

    private final SubmissionRepository submissions;
    private final TaskApplicationRepository apps;
    private final TaskRepository tasks;
    private final ConfirmationWorkflowStarter starter;
    private final int batchSize;

    public ConfirmationWindowDispatcher(
            SubmissionRepository submissions,
            TaskApplicationRepository apps,
            TaskRepository tasks,
            ConfirmationWorkflowStarter starter,
            @org.springframework.beans.factory.annotation.Value(
                    "${marketplace.confirmation.dispatcher-batch-size:32}") int batchSize) {
        this.submissions = submissions;
        this.apps = apps;
        this.tasks = tasks;
        this.starter = starter;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(fixedDelayString = "${marketplace.confirmation.dispatcher-poll-ms:2000}")
    public void dispatch() {
        Mono.fromRunnable(this::dispatchBatch)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    void dispatchBatch() {
        List<EngagementSubmission> rows =
                submissions.findConfirmationDispatchable(batchSize).collectList().block();
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (EngagementSubmission submission : rows) {
            dispatchOne(submission);
        }
    }

    private void dispatchOne(EngagementSubmission submission) {
        try {
            TaskApplication app = apps.findById(submission.applicationId()).block();
            if (app == null || app.confirmedAt() != null || app.merchantConfirmDeadlineAt() == null) {
                return;
            }
            var task = tasks.findById(app.taskId()).block();
            if (task == null) {
                log.warn("confirmation dispatch task missing submission={} app={}", submission.id(), app.id());
                return;
            }
            long remainingSeconds = Math.max(0,
                    Duration.between(Instant.now(), app.merchantConfirmDeadlineAt()).toSeconds());
            starter.start(app.id(), submission.id(), task.organizationId(), remainingSeconds).block();
            submissions.markConfirmationWorkflowStarted(submission.id()).block();
        } catch (RuntimeException failure) {
            // 不标记，下轮重试；确定性 workflowId 保证「start 已成功但 mark 失败」也安全。
            log.warn("confirmation workflow dispatch failed submission={} app={}",
                    submission.id(), submission.applicationId(), failure);
        }
    }
}
