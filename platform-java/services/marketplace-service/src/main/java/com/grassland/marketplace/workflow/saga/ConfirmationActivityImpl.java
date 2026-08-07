package com.grassland.marketplace.workflow.saga;

import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.taskcatalog.EngagementSubmission;
import com.grassland.marketplace.taskcatalog.SubmissionRepository;
import com.grassland.marketplace.taskcatalog.SubmissionStatus;
import com.grassland.marketplace.taskcatalog.Task;
import com.grassland.marketplace.taskcatalog.TaskApplication;
import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
import com.grassland.marketplace.taskcatalog.TaskRepository;
import io.temporal.spring.boot.ActivityImpl;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;

/**
 * 商家确认窗口 Saga 活动实现（D-03）。窗口到期执行（幂等 + 重验）。
 *
 * <p><b>submission 绑定</b>：每个 workflow 绑定具体 {@code submissionId}。若该提交被退回，旧 Timer 到期见
 * submission 非 submitted 即 abort；重交会启动新的 workflow，避免旧窗口错误结算新凭证。
 *
 * <p><b>幂等/竞态</b>：首次自动路径把 submission accepted + application autoConfirm +
 * {@code AutoSettledOnTimeout} outbox 放同一事务；{@code auto_confirmed_at} 区分自动确认与商家手动确认。
 * activity 在确认后 crash 重试时见 auto_confirmed_at 非空可继续幂等 capture；仅 confirmed_at 非空则说明商家先确认，abort。
 */
@Component
@ActivityImpl(workers = ApplicationReservationWorkflowImpl.TASK_QUEUE)
public class ConfirmationActivityImpl implements ConfirmationActivity {

    private final TaskApplicationRepository apps;
    private final TaskRepository tasks;
    private final SubmissionRepository submissions;
    private final OutboxRepository outbox;
    private final SettlementWorkflowStarter settlementWorkflows;
    private final TransactionalOperator transactions;

    public ConfirmationActivityImpl(TaskApplicationRepository apps, TaskRepository tasks,
                                    SubmissionRepository submissions, OutboxRepository outbox,
                                    SettlementWorkflowStarter settlementWorkflows, TransactionalOperator transactions) {
        this.apps = apps;
        this.tasks = tasks;
        this.submissions = submissions;
        this.outbox = outbox;
        this.settlementWorkflows = settlementWorkflows;
        this.transactions = transactions;
    }

    @Override
    public ConfirmationOutcome autoConfirmSettle(ConfirmationInput input) {
        TaskApplication app = apps.findById(input.applicationId()).block();
        if (app == null || !"accepted".equals(app.status())) {
            return ConfirmationOutcome.aborted();
        }
        // contest durable claim 先赢：不依赖 trust 可见性，Timer 必须在本地直接停住且绝不 capture。
        if (app.contestRequestedAt() != null) {
            return ConfirmationOutcome.held("merchant_contest_requested");
        }
        // 商家手动确认先赢（confirmed_at 有值但 auto_confirmed_at 无值）→ 由其 SettlementWindow 接管，本 workflow abort。
        if (app.confirmedAt() != null && app.autoConfirmedAt() == null) {
            return ConfirmationOutcome.aborted();
        }

        final TaskApplication loadedApp = app;
        Task task = tasks.findById(loadedApp.taskId()).block();
        String taskOwnerId = task == null ? null : task.ownerAccountId();

        if (loadedApp.autoConfirmedAt() == null) {
            // 首次自动确认：本 submission 必须仍 submitted；review + autoConfirm + outbox 同事务。
            TaskApplication autoConfirmed = transactions.transactional(
                    submissions.findById(input.submissionId())
                            .filter(s -> loadedApp.id().equals(s.applicationId())
                                    && SubmissionStatus.SUBMITTED.dbValue().equals(s.status()))
                            .flatMap(s -> submissions.review(s.id(), SubmissionStatus.ACCEPTED, null))
                            // 只有 guarded review 真正成功，才允许自动确认；empty 不能用 then 穿透到 autoConfirm。
                            .flatMap(reviewed -> apps.autoConfirm(loadedApp.id(), loadedApp.taskId()))
                            .flatMap(confirmed -> outbox.append(envelope(
                                            "AutoSettledOnTimeout", confirmed, input.submissionId(), taskOwnerId))
                                    .thenReturn(confirmed))).block();
            if (autoConfirmed == null) {
                // submission 被退回/商家先确认/状态已变，本窗口失效。
                return ConfirmationOutcome.aborted();
            }
            app = autoConfirmed;
        }

        // Auto-confirm and manual-confirm now share the same entitlement-derived settlement timer.
        String workflowId = settlementWorkflows.start(app.taskId(), input.organizationId(), app).block();
        return workflowId == null ? ConfirmationOutcome.aborted() : ConfirmationOutcome.autoSettled();
    }

    /**
     * 临到期提醒（D-03 §1 剩余 24h 强提醒）。发 outbox {@code ConfirmationWindowExpiring}（双方收件）。
     *
     * <p>跳过条件（窗口已失效，提醒无意义）：app 已确认 / 非 accepted / 本 submission 已退回。确定性 eventId
     * （type-3 {@code ConfirmationWindowExpiring:<submissionId>}）保证 activity 重试 / Temporal 重放不重复通知。
     */
    @Override
    public void notifyExpiring(ConfirmationInput input) {
        TaskApplication app = apps.findById(input.applicationId()).block();
        if (app == null || !"accepted".equals(app.status()) || app.confirmedAt() != null) {
            return;  // 已确认 / 非 accepted / 不存在 → 窗口已失效
        }
        EngagementSubmission submission = submissions.findById(input.submissionId()).block();
        if (submission == null || !SubmissionStatus.SUBMITTED.dbValue().equals(submission.status())) {
            return;  // 本 submission 已退回（重交起新窗口）→ 旧窗口的提醒无意义
        }
        Task task = tasks.findById(app.taskId()).block();
        String taskOwnerId = task == null ? null : task.ownerAccountId();
        outbox.append(envelope("ConfirmationWindowExpiring", app, input.submissionId(), taskOwnerId)).block();
    }

    private EventEnvelope envelope(String eventType, TaskApplication app, String submissionId, String taskOwnerId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", app.taskId());
        payload.put("applicationId", app.id());
        payload.put("submissionId", submissionId);
        payload.put("recommenderAccountId", app.recommenderAccountId());
        payload.put("status", app.status());
        if (taskOwnerId != null) {
            payload.put("taskOwnerId", taskOwnerId);
        }
        String eventId = UUID.nameUUIDFromBytes(
                (eventType + ":" + submissionId).getBytes(StandardCharsets.UTF_8)).toString();
        return new EventEnvelope(eventId, eventType, "TaskApplication",
                app.id(), 1, Instant.now(), null, payload);
    }
}
