package com.grassland.marketplace.workflow.saga;

import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.taskcatalog.SubmissionRepository;
import com.grassland.marketplace.taskcatalog.Task;
import com.grassland.marketplace.taskcatalog.TaskApplication;
import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
import com.grassland.marketplace.taskcatalog.TaskRepository;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import io.temporal.spring.boot.ActivityImpl;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 交付看门狗活动实现（任务书 #96 C96-01）。照 {@link ConfirmationActivityImpl} 惯例：
 * 每次执行前重验行级状态，guard 烧进条件 UPDATE（0 行 = 已被并发路径赢走 → abort），outbox 与领域写同事务，
 * 跨服务 finance 调用留事务外（幂等 + 重试收敛）。
 *
 * <p>有责终结的资金方向（§4.1.4 释放余款）：bounty 腿 release 返商家；freebie 腿 freebieCompensate
 * （押金判商家——finance 既有「未达标/商家获判」语义）。无责退出（退推荐官）走
 * {@code ApplicationLifecycleService#exitNoFault}，两条路径共用行级守卫互斥。
 */
@Component
@ActivityImpl(workers = ApplicationReservationWorkflowImpl.TASK_QUEUE)
public class DeliveryLifecycleActivityImpl implements DeliveryLifecycleActivity {

    private final TaskApplicationRepository apps;
    private final TaskRepository tasks;
    private final SubmissionRepository submissions;
    private final OutboxRepository outbox;
    private final FinanceEscrowClient finance;
    private final com.grassland.marketplace.taskcatalog.TaskAcceptanceCounterRepository counters;
    private final TransactionalOperator transactions;

    public DeliveryLifecycleActivityImpl(TaskApplicationRepository apps, TaskRepository tasks,
            SubmissionRepository submissions, OutboxRepository outbox, FinanceEscrowClient finance,
            com.grassland.marketplace.taskcatalog.TaskAcceptanceCounterRepository counters,
            TransactionalOperator transactions) {
        this.apps = apps;
        this.tasks = tasks;
        this.submissions = submissions;
        this.outbox = outbox;
        this.finance = finance;
        this.counters = counters;
        this.transactions = transactions;
    }

    @Override
    public void notifyDeliveryExpiring(DeliveryDeadlineInput input) {
        TaskApplication app = apps.findById(input.applicationId()).block();
        if (app == null || !"accepted".equals(app.status()) || app.confirmedAt() != null
                || app.exitedAt() != null) {
            return;  // 窗口已失效
        }
        boolean hasSubmission = submissions.findByApplication(app.id()).hasElements().block();
        if (hasSubmission) {
            return;  // 已进凭证/确认阶段，交付期已履行
        }
        Task task = tasks.findById(app.taskId()).block();
        String taskOwnerId = task == null ? null : task.ownerAccountId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", app.taskId());
        payload.put("applicationId", app.id());
        payload.put("recommenderAccountId", app.recommenderAccountId());
        payload.put("deliveryDeadlineAt", app.deliveryDeadlineAt() == null ? null : app.deliveryDeadlineAt().toString());
        payload.put("remedyDeadlineAt", app.remedyDeadlineAt() == null ? null : app.remedyDeadlineAt().toString());
        if (taskOwnerId != null) {
            payload.put("taskOwnerId", taskOwnerId);
        }
        String eventId = UUID.nameUUIDFromBytes(
                ("DeliveryDeadlineExpiring:" + app.id() + ":"
                        + (app.deliveryDeadlineAt() == null ? "" : app.deliveryDeadlineAt().getEpochSecond()))
                        .getBytes(StandardCharsets.UTF_8)).toString();
        outbox.append(new EventEnvelope(eventId, "DeliveryDeadlineExpiring", "TaskApplication",
                app.id(), 1, Instant.now(), null, payload)).block();
    }

    @Override
    public DeliveryOutcome terminateDeliveryTimeout(DeliveryDeadlineInput input) {
        TaskApplication app = apps.findById(input.applicationId()).block();
        if (app == null || !"accepted".equals(app.status()) || app.confirmedAt() != null
                || app.exitedAt() != null) {
            return DeliveryOutcome.aborted();
        }
        boolean hasSubmission = submissions.findByApplication(app.id()).hasElements().block();
        if (hasSubmission) {
            return DeliveryOutcome.aborted();  // 补救窗内已交付 → 交付期已履行，进确认窗口
        }
        Task task = tasks.findById(app.taskId()).block();
        if (task == null) {
            return DeliveryOutcome.aborted();
        }
        String taskOwnerId = task.ownerAccountId();
        // 资金腿先落（事务外，幂等）：本单已全额释放——无确认里程碑即无阶段结算（里程碑结算随 C96-02 接入）。
        if (app.freebieDepositCents() > 0) {
            finance.freebieCompensate(task.organizationId(), app.id()).block();
        }
        if (app.bountyCents() > 0) {
            finance.release(task.organizationId(), app.id()).block();
        }
        // guarded 终结 + outbox + 名额回收同事务；0 行 = 延期/确认/退出/取消抢先，单边胜出。
        TaskApplication terminated = transactions.transactional(
                apps.markDeliveryTimedOut(app.id(), task.id())
                        .flatMap(done -> counters.release(task.id())
                                .filter(Boolean::booleanValue)
                                .switchIfEmpty(Mono.error(new IllegalStateException("acceptance counter underflow")))
                                .then(outbox.append(terminatedEnvelope(task, done, taskOwnerId)))
                                .thenReturn(done)))
                .block();
        return terminated == null ? DeliveryOutcome.aborted() : DeliveryOutcome.terminated();
    }

    private EventEnvelope terminatedEnvelope(Task task, TaskApplication app, String taskOwnerId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.id());
        payload.put("applicationId", app.id());
        payload.put("recommenderAccountId", app.recommenderAccountId());
        payload.put("exitKind", app.exitKind());
        payload.put("exitedAt", app.exitedAt() == null ? null : app.exitedAt().toString());
        payload.put("reason", "delivery_timeout");
        if (taskOwnerId != null) {
            payload.put("taskOwnerId", taskOwnerId);
        }
        String eventId = UUID.nameUUIDFromBytes(
                ("DeliveryTimeoutTerminated:" + app.id()).getBytes(StandardCharsets.UTF_8)).toString();
        return new EventEnvelope(eventId, "DeliveryTimeoutTerminated", "TaskApplication",
                app.id(), 1, Instant.now(), null, payload);
    }
}
