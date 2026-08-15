package com.grassland.marketplace.workflow.saga;

import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.ops.OpsCaseRegistrar;
import com.grassland.marketplace.ops.OpsCaseSource;
import com.grassland.marketplace.taskcatalog.SubmissionRepository;
import com.grassland.marketplace.taskcatalog.SubmissionStatus;
import com.grassland.marketplace.taskcatalog.Task;
import com.grassland.marketplace.taskcatalog.TaskApplication;
import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
import com.grassland.marketplace.workflow.TrustDisputeClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * F6 contest durable intent 的恢复执行器。claim 已在本地提交后，依次幂等完成 trust 开案、本地 contest 领域写与 SLA 启窗；
 * 所有远端调用均在 marketplace 事务外，任一步失败都保留 claim 供 HTTP 重试或 dispatcher 补偿。
 */
@Component
public class MerchantContestCoordinator {

    private final TaskApplicationRepository apps;
    private final SubmissionRepository submissions;
    private final TrustDisputeClient trust;
    private final OpsCaseRegistrar opsCases;
    private final OutboxRepository outbox;
    private final MerchantRejectionReviewWorkflowStarter workflows;
    private final TransactionalOperator transactions;
    private final long csSlaSeconds;
    private final int csSlaBusinessDays;
    private final BusinessDayCalendar calendar;

    @org.springframework.beans.factory.annotation.Autowired
    public MerchantContestCoordinator(
            TaskApplicationRepository apps,
            SubmissionRepository submissions,
            TrustDisputeClient trust,
            OpsCaseRegistrar opsCases,
            OutboxRepository outbox,
            MerchantRejectionReviewWorkflowStarter workflows,
            TransactionalOperator transactions,
            @org.springframework.beans.factory.annotation.Value(
                    "${marketplace.confirmation.cs-sla-seconds:0}") long csSlaSeconds,
            @org.springframework.beans.factory.annotation.Value(
                    "${marketplace.confirmation.cs-sla-business-days:3}") int csSlaBusinessDays,
            BusinessDayCalendar calendar) {
        this.apps = apps;
        this.submissions = submissions;
        this.trust = trust;
        this.opsCases = opsCases;
        this.outbox = outbox;
        this.workflows = workflows;
        this.transactions = transactions;
        this.csSlaSeconds = Math.max(0, csSlaSeconds);
        this.csSlaBusinessDays = Math.max(0, csSlaBusinessDays);
        this.calendar = calendar;
    }

    /** Test/backward-compatible constructor; production wiring uses the calendar-aware overload. */
    MerchantContestCoordinator(TaskApplicationRepository apps, SubmissionRepository submissions,
            TrustDisputeClient trust, OpsCaseRegistrar opsCases, OutboxRepository outbox,
            MerchantRejectionReviewWorkflowStarter workflows, TransactionalOperator transactions,
            long csSlaSeconds) {
        this(apps, submissions, trust, opsCases, outbox, workflows, transactions, csSlaSeconds, 3,
                new BusinessDayCalendar(Set.of(), Set.of()));
    }

    public Mono<TaskApplication> dispatch(TaskApplication claimed, Task task) {
        if (claimed.contestRequestedAt() == null) {
            return Mono.error(new IllegalStateException("contest intent is not claimed"));
        }
        Mono<TaskApplication> completed = claimed.merchantRejectionDisputeId() == null
                ? openTrustCase(claimed, task)
                        .flatMap(disputeId -> completeLocally(claimed, task, disputeId))
                : Mono.just(claimed);
        return completed.flatMap(app -> workflows.start(
                        app.id(), app.merchantRejectionDisputeId(), task.organizationId(), slaSeconds())
                .then(Mono.defer(() -> apps.markRejectionWorkflowStarted(
                        app.id(), app.merchantRejectionDisputeId())))
                .then(Mono.defer(() -> apps.findById(app.id()))));
    }

    private Mono<String> openTrustCase(TaskApplication claimed, Task task) {
        if (Boolean.TRUE.equals(claimed.premiumSupportAtAccept())) {
            return trust.openMerchantRejection(task.organizationId(), claimed.id(), task.ownerAccountId(),
                    claimed.rejectionReason(), claimed.recommenderAccountId(), true);
        }
        return trust.openMerchantRejection(task.organizationId(), claimed.id(), task.ownerAccountId(),
                claimed.rejectionReason());
    }

    private Mono<TaskApplication> completeLocally(TaskApplication claimed, Task task, String disputeId) {
        Mono<TaskApplication> work = submissions.findPending(claimed.id())
                .switchIfEmpty(Mono.error(new IllegalStateException("contest submission is no longer pending")))
                .flatMap(submission -> submissions.review(submission.id(), SubmissionStatus.ACCEPTED, null)
                        .switchIfEmpty(Mono.defer(() -> submissions.findById(submission.id())
                                .filter(s -> SubmissionStatus.ACCEPTED.dbValue().equals(s.status()))))
                        .switchIfEmpty(Mono.error(new IllegalStateException("contest submission state changed")))
                        .then(apps.completeContest(claimed.id(), claimed.taskId(), disputeId))
                        .switchIfEmpty(Mono.error(new IllegalStateException("contest claim state changed")))
                        .flatMap(contested -> opsCases.register(
                                        OpsCaseSource.MERCHANT_REJECTION, disputeId, task.organizationId(),
                                        contested.id(), contested.rejectionReason())
                                .then(outbox.append(merchantContestedEnvelope(
                                        contested, submission.id(), disputeId, task.ownerAccountId())))
                                .thenReturn(contested)));
        // transactional Mono 的 onNext 早于 commit；用 then+defer 确保后续 Temporal 启动只看已提交状态。
        return transactions.transactional(work)
                .then(Mono.defer(() -> apps.findById(claimed.id())))
                .filter(app -> disputeId.equals(app.merchantRejectionDisputeId()))
                .switchIfEmpty(Mono.error(new IllegalStateException("contest commit not visible")));
    }

    private long slaSeconds() {
        if (csSlaSeconds > 0) return csSlaSeconds;
        Instant now = Instant.now();
        return calendar.secondsUntil(calendar.addBusinessDays(now, csSlaBusinessDays), now);
    }

    private EventEnvelope merchantContestedEnvelope(
            TaskApplication app, String submissionId, String disputeId, String taskOwnerId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", app.taskId());
        payload.put("applicationId", app.id());
        payload.put("submissionId", submissionId);
        payload.put("disputeId", disputeId);
        payload.put("recommenderAccountId", app.recommenderAccountId());
        payload.put("status", "contested");
        payload.put("reason", app.rejectionReason());
        if (taskOwnerId != null) {
            payload.put("taskOwnerId", taskOwnerId);
        }
        String eventId = UUID.nameUUIDFromBytes(
                ("MerchantContested:" + app.id()).getBytes(StandardCharsets.UTF_8)).toString();
        return new EventEnvelope(eventId, "MerchantContested", "TaskApplication",
                app.id(), 1, Instant.now(), null, payload);
    }
}
