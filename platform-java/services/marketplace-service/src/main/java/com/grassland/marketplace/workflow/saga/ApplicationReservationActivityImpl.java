package com.grassland.marketplace.workflow.saga;

import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.taskcatalog.ApplicationStatus;
import com.grassland.marketplace.taskcatalog.AcceptanceCommand;
import com.grassland.marketplace.taskcatalog.AcceptanceCommandRepository;
import com.grassland.marketplace.taskcatalog.Task;
import com.grassland.marketplace.taskcatalog.TaskAcceptanceCounterRepository;
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
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 接受报名资金预留 Saga 的活动实现（草场 Epic 4 Slice 4F / HLD 9.2、10.2）。
 *
 * <p>每个活动幂等 + 执行前重验状态（replay/重试安全）：条件 UPDATE 空结果=已变迁→跳过；outbox 用确定性 event_id
 * （type-3 UUID from {@code eventType:applicationId}）让 activity 重试时 {@code ON CONFLICT (event_id) DO NOTHING} 去重，
 * 保留 exactly-once 外观。activity 内可阻塞调 reactive repo / finance（Temporal 活动在独立线程池跑，非 workflow 线程）。
 *
 * <p><b>Slice 7C-2</b>：每个写活动的「领域写 + outbox append」绑进同一 R2DBC 事务
 * （{@code transactions.transactional(写.flatMap(outbox))}）——否则崩溃落在两次提交之间时，重试会被幂等守卫短路，
 * 事件**永久丢失**。守卫（重验状态 / 名额 / owner）与跨服务 {@code finance.*} 调用留在事务外。
 */
@Component
@ActivityImpl(workers = ApplicationReservationWorkflowImpl.TASK_QUEUE)
public class ApplicationReservationActivityImpl implements ApplicationReservationActivity {

    private static final Logger log = LoggerFactory.getLogger(ApplicationReservationActivityImpl.class);

    private final TaskApplicationRepository apps;
    private final TaskAcceptanceCounterRepository counters;
    private final AcceptanceCommandRepository commands;
    private final TaskRepository tasks;
    private final OutboxRepository outbox;
    private final FinanceEscrowClient finance;
    private final TransactionalOperator transactions;

    public ApplicationReservationActivityImpl(TaskApplicationRepository apps,
                                              TaskAcceptanceCounterRepository counters,
                                              AcceptanceCommandRepository commands,
                                              TaskRepository tasks,
                                              OutboxRepository outbox, FinanceEscrowClient finance,
                                              TransactionalOperator transactions) {
        this.apps = apps;
        this.counters = counters;
        this.commands = commands;
        this.tasks = tasks;
        this.outbox = outbox;
        this.finance = finance;
        this.transactions = transactions;
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
        if (input.commandId() != null) {
            AcceptanceCommand command = commands.findById(input.commandId()).block();
            return command != null
                    && command.applicationId().equals(input.applicationId())
                    && ApplicationStatus.RESERVING.dbValue().equals(status)
                    && ("pending_dispatch".equals(command.status()) || "started".equals(command.status()));
        }
        if (ApplicationStatus.RESERVING.dbValue().equals(status)) {
            return true;  // 重试幂等：已在 reserving
        }
        if (!ApplicationStatus.PENDING.dbValue().equals(status)) {
            return false;  // 已终态（accepted/rejected/withdrawn）
        }
        // Rolling-upgrade path for workflows serialized before the command ledger existed.
        TaskApplication transitioned = transactions.transactional(
                counters.claim(input.taskId())
                        .flatMap(ignored -> apps.beginAcceptance(
                                input.applicationId(), input.taskId(), input.effectiveOperatorAccountId()))
                        .flatMap(t -> outbox.append(envelope("ApplicationAcceptanceStarted", t, null)).thenReturn(t))
        ).block();
        return transitioned != null;  // null = 竞态空结果（empty Mono，无写无事件）
    }

    @Override
    public ReserveResult reserveFunds(AcceptanceInput input) {
        log.info("reserveFunds START org={} ref={} amount={}", input.organizationId(), input.applicationId(), input.amountCents());
        try {
            // 收款人从报名记录现查，而不是加进 AcceptanceInput——workflow 入参变更会影响在途实例的反序列化，
            // 而这里本来就要读库，多取一个字段是零成本。
            TaskApplication payeeApp = apps.findById(input.applicationId()).block();
            String payee = payeeApp == null ? null : payeeApp.recommenderAccountId();
            int commissionBonusBps = payeeApp == null || payeeApp.commissionBonusBpsAtAccept() == null
                    ? 0 : payeeApp.commissionBonusBpsAtAccept();
            ReserveResult r = commissionBonusBps == 0
                    ? finance.reserve(input.organizationId(), input.applicationId(), input.amountCents(), payee).block()
                    : finance.reserve(input.organizationId(), input.applicationId(), input.amountCents(), payee,
                            commissionBonusBps).block();
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
        // 领域写（reserving→accepted）+ outbox 同事务。冻结 accept 时赏金（input.amountCents = accept 时 task 赏金）：
        // 此后结算读 app.bountyCents() 而非可变 task 行——accept 后改 task 赏金不再影响本履约。
        TaskApplication activated = transactions.transactional(
                apps.acceptFromReserving(input.applicationId(), input.taskId(), input.amountCents())
                        .flatMap(a -> markCommandAccepted(input)
                                .then(outbox.append(envelope("ApplicationAccepted", a, null, input.commandId())))
                                .thenReturn(a))
        ).block();
        if (activated == null) {
            return;  // 竞态：已变迁（empty Mono，无写无事件）
        }
    }

    @Override
    public void compensateAcceptance(AcceptanceInput input, ReserveResult reserve, String reason) {
        if (reserve.reserved()) {
            // release idempotent：已释放(409)/不存在(404) 由 client 映射为成功；瞬态失败抛异常→Temporal 重试本 activity。
            // 跨服务调用，不进本地事务（与本地 revertReserving+outbox 原子性正交）。
            finance.release(input.organizationId(), input.applicationId()).block();
        }
        TaskApplication app = apps.findById(input.applicationId()).block();
        if (app == null || !ApplicationStatus.RESERVING.dbValue().equals(app.status())) {
            return;  // 已回退/不在 reserving（幂等）
        }
        // 领域写（reserving→pending）+ outbox 同事务。
        TaskApplication reverted = transactions.transactional(
                apps.revertReserving(input.applicationId(), input.taskId())
                        .flatMap(r -> counters.release(input.taskId())
                                .filter(Boolean::booleanValue)
                                .switchIfEmpty(Mono.error(new IllegalStateException("acceptance counter underflow")))
                                .then(markCommandCompensated(input, reason))
                                .then(outbox.append(envelope(
                                        "ApplicationReservationFailed", r, reason, input.commandId())))
                                .thenReturn(r))
        ).block();
        if (reverted == null) {
            return;  // 竞态：已回退（empty Mono，无写无事件）
        }
    }

    private EventEnvelope envelope(String eventType, TaskApplication app, String reason) {
        return envelope(eventType, app, reason, null);
    }

    private EventEnvelope envelope(String eventType, TaskApplication app, String reason, String commandId) {
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
        String eventId = UUID.nameUUIDFromBytes((eventType + ":" + app.id()
                + (commandId == null ? "" : ":" + commandId)).getBytes(StandardCharsets.UTF_8)).toString();
        return new EventEnvelope(eventId, eventType, "TaskApplication",
                app.id(), 1, Instant.now(), null, payload);
    }

    private Mono<Boolean> markCommandAccepted(AcceptanceInput input) {
        return input.commandId() == null ? Mono.just(true) : commands.markAccepted(input.commandId());
    }

    private Mono<Boolean> markCommandCompensated(AcceptanceInput input, String reason) {
        return input.commandId() == null ? Mono.just(true) : commands.markCompensated(input.commandId(), reason);
    }
}
