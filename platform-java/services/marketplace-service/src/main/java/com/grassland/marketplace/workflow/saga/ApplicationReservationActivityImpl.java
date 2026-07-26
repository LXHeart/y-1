package com.grassland.marketplace.workflow.saga;

import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.taskcatalog.ApplicationStatus;
import com.grassland.marketplace.taskcatalog.Task;
import com.grassland.marketplace.taskcatalog.TaskApplication;
import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
import com.grassland.marketplace.taskcatalog.TaskRepository;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import io.temporal.spring.boot.ActivityImpl;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 接受报名资金预留 Saga 的活动实现（草场 Epic 4 Slice 4F / HLD 9.2、10.2）。
 *
 * <p>每个活动幂等 + 执行前重验状态（replay/重试安全）：条件 UPDATE 空结果=已变迁→跳过；outbox 用确定性 event_id
 * （type-3 UUID from {@code eventType:applicationId}）让 activity 重试时 {@code ON CONFLICT (event_id) DO NOTHING} 去重，
 * 保留 exactly-once 外观。activity 内可阻塞调 reactive repo / finance（Temporal 活动在独立线程池跑，非 workflow 线程）。
 */
@Component
@ActivityImpl(workers = ApplicationReservationWorkflowImpl.TASK_QUEUE)
public class ApplicationReservationActivityImpl implements ApplicationReservationActivity {

    private static final Logger log = LoggerFactory.getLogger(ApplicationReservationActivityImpl.class);

    private final TaskApplicationRepository apps;
    private final TaskRepository tasks;
    private final OutboxRepository outbox;
    private final FinanceEscrowClient finance;

    public ApplicationReservationActivityImpl(TaskApplicationRepository apps, TaskRepository tasks,
                                              OutboxRepository outbox, FinanceEscrowClient finance) {
        this.apps = apps;
        this.tasks = tasks;
        this.outbox = outbox;
        this.finance = finance;
    }

    @Override
    public boolean beginAcceptance(AcceptanceInput input) {
        log.info("beginAcceptance START app={} task={}", input.applicationId(), input.taskId());
        Task task = tasks.findById(input.taskId()).block();
        if (task == null || !input.merchantAccountId().equals(task.ownerAccountId())) {
            return false;  // 任务不存在 / 非 owner（重验 HLD 7.4）
        }
        TaskApplication app = apps.findById(input.applicationId()).block();
        if (app == null || !input.taskId().equals(app.taskId())) {
            return false;  // 报名不存在 / 越界
        }
        String status = app.status();
        if (ApplicationStatus.RESERVING.dbValue().equals(status)) {
            return true;  // 重试幂等：已在 reserving
        }
        if (!ApplicationStatus.PENDING.dbValue().equals(status)) {
            return false;  // 已终态（accepted/rejected/withdrawn）
        }
        Integer max = task.maxSlots();
        if (max != null) {
            Integer accepted = apps.countAcceptedByTask(input.taskId()).block();
            if (accepted != null && accepted >= max) {
                return false;  // 名额已满（多 acceptor 竞态兜底）
            }
        }
        TaskApplication transitioned = apps.beginAcceptance(
                input.applicationId(), input.taskId(), input.merchantAccountId()).block();
        if (transitioned == null) {
            return false;  // 竞态：已被他人变迁
        }
        outbox.append(envelope("ApplicationAcceptanceStarted", transitioned, null)).block();
        return true;
    }

    @Override
    public ReserveResult reserveFunds(AcceptanceInput input) {
        log.info("reserveFunds START org={} ref={} amount={}", input.organizationId(), input.applicationId(), input.amountCents());
        try {
            // 收款人从报名记录现查，而不是加进 AcceptanceInput——workflow 入参变更会影响在途实例的反序列化，
            // 而这里本来就要读库，多取一个字段是零成本。
            TaskApplication payeeApp = apps.findById(input.applicationId()).block();
            String payee = payeeApp == null ? null : payeeApp.recommenderAccountId();
            ReserveResult r = finance.reserve(
                    input.organizationId(), input.applicationId(), input.amountCents(), payee).block();
            log.info("reserveFunds RESULT {}", r);
            return r;
        } catch (RuntimeException e) {
            log.error("reserveFunds FAILED ref={}", input.applicationId(), e);
            throw e;
        }
    }

    @Override
    public void activateEngagement(AcceptanceInput input) {
        TaskApplication app = apps.findById(input.applicationId()).block();
        if (app == null) {
            return;
        }
        if (ApplicationStatus.ACCEPTED.dbValue().equals(app.status())) {
            return;  // 重试幂等：已激活
        }
        if (!ApplicationStatus.RESERVING.dbValue().equals(app.status())) {
            return;  // 已补偿回 pending 或其他——无可激活
        }
        TaskApplication activated = apps.acceptFromReserving(input.applicationId(), input.taskId()).block();
        if (activated == null) {
            return;  // 竞态：已变迁
        }
        outbox.append(envelope("ApplicationAccepted", activated, null)).block();
    }

    @Override
    public void compensateAcceptance(AcceptanceInput input, ReserveResult reserve, String reason) {
        if (reserve.reserved()) {
            // release idempotent：已释放(409)/不存在(404) 由 client 映射为成功；瞬态失败抛异常→Temporal 重试本 activity
            finance.release(input.organizationId(), input.applicationId()).block();
        }
        TaskApplication app = apps.findById(input.applicationId()).block();
        if (app == null || !ApplicationStatus.RESERVING.dbValue().equals(app.status())) {
            return;  // 已回退/不在 reserving（幂等）
        }
        TaskApplication reverted = apps.revertReserving(input.applicationId(), input.taskId()).block();
        if (reverted == null) {
            return;  // 竞态：已回退
        }
        outbox.append(envelope("ApplicationReservationFailed", reverted, reason)).block();
    }

    private EventEnvelope envelope(String eventType, TaskApplication app, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", app.taskId());
        payload.put("applicationId", app.id());
        payload.put("recommenderAccountId", app.recommenderAccountId());
        payload.put("status", app.status());
        payload.put("reviewedByAccountId", app.reviewedByAccountId());
        if (reason != null) {
            payload.put("reason", reason);
        }
        // 确定性 event_id（type-3 UUID from eventType:applicationId）——activity 重试时 ON CONFLICT 去重
        String eventId = UUID.nameUUIDFromBytes(
                (eventType + ":" + app.id()).getBytes(StandardCharsets.UTF_8)).toString();
        return new EventEnvelope(eventId, eventType, "TaskApplication",
                app.id(), 1, Instant.now(), null, payload);
    }
}
