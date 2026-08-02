package com.grassland.marketplace.workflow.saga;

import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.ops.OpsCaseRegistrar;
import com.grassland.marketplace.ops.OpsCaseSource;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;

/**
 * 结算窗口 Saga 活动实现（草场 Epic 5 Slice 5A / HLD 9.2、10.3）。
 *
 * <p>窗口到期后执行（幂等 + 重验）：重验 accepted+confirmed → 查争议（{@link DisputeChecker} seam）→
 * 无争议则 finance capture（reserved→captured）+ outbox {@code EngagementSettled}；有争议则 outbox
 * {@code SettlementHeld}。outbox 用确定性 event_id（type-3 UUID）保重试 exactly-once。
 *
 * <p><b>hold 同时登记运营处置单</b>（GL-P1-OPS-001 Stage 1）：此前 hold 只有一条 outbox 事件，
 * 没有任何持久行，运营无从知道当前有多少笔被暂缓。现在 outbox append 与 ops_case 登记同事务，
 * 保证「暂缓发生 ⇔ 处置单存在」。
 */
@Component
@ActivityImpl(workers = ApplicationReservationWorkflowImpl.TASK_QUEUE)
public class SettlementActivityImpl implements SettlementActivity {

    private static final Logger log = LoggerFactory.getLogger(SettlementActivityImpl.class);

    private final TaskApplicationRepository apps;
    private final TaskRepository tasks;
    private final OutboxRepository outbox;
    private final FinanceEscrowClient finance;
    private final DisputeChecker disputes;
    private final VerificationChecker verification;
    private final OpsCaseRegistrar opsCases;
    private final TransactionalOperator transactions;

    public SettlementActivityImpl(TaskApplicationRepository apps, TaskRepository tasks, OutboxRepository outbox,
                                  FinanceEscrowClient finance, DisputeChecker disputes,
                                  VerificationChecker verification, OpsCaseRegistrar opsCases,
                                  TransactionalOperator transactions) {
        this.apps = apps;
        this.tasks = tasks;
        this.outbox = outbox;
        this.finance = finance;
        this.disputes = disputes;
        this.verification = verification;
        this.opsCases = opsCases;
        this.transactions = transactions;
    }

    @Override
    public SettlementOutcome captureSettlement(SettlementInput input) {
        TaskApplication app = apps.findById(input.applicationId()).block();
        if (app == null) {
            return SettlementOutcome.aborted();
        }
        if (!"accepted".equals(app.status()) || app.confirmedAt() == null) {
            return SettlementOutcome.aborted();  // 非 accepted+confirmed（被回退/未确认/已结算）
        }
        // 任务归属用于通知收件人解析（Slice 12 Stage 3）；任务缺失则置空，不阻断结算主路径。
        Task task = tasks.findById(app.taskId()).block();
        String taskOwnerId = task == null ? null : task.ownerAccountId();
        if (disputes.hasOpenDispute(input.organizationId(), input.applicationId())) {
            return hold(input, app, "open_dispute", taskOwnerId);  // HLD 16：结算执行前重新检查 Hold
        }
        // 履约核验安全网闸门（Verification v1）：与争议闸门正交，failed 核验 → hold（inconclusive/passed/无记录不阻断）。
        if (verification.blocksSettlement(input.organizationId(), input.applicationId())) {
            return hold(input, app, "verification_failed", taskOwnerId);
        }
        log.info("settlement capture START org={} ref={}", input.organizationId(), input.applicationId());
        // finance reserved→captured；409(已终态 captured/released) 由 client 映射为成功（幂等）；真异常抛出→Temporal 重试
        finance.capture(input.organizationId(), input.applicationId()).block();
        outbox.append(envelope("EngagementSettled", app, null, taskOwnerId)).block();
        return SettlementOutcome.settled();
    }

    /**
     * 暂缓：outbox {@code SettlementHeld} + 运营处置单登记，**同一事务**（GL-P1-OPS-001 Stage 1）。
     *
     * <p>两者都幂等（outbox 用确定性 event_id，ops_case 用 {@code UNIQUE(source_kind, source_ref)}），
     * 故 activity 重跑安全。sourceRef 取 {@code applicationId} —— 同一笔履约的反复暂缓归并到一张单，
     * 而不是每次窗口重试都开新单。
     */
    private SettlementOutcome hold(SettlementInput input, TaskApplication app, String reason, String taskOwnerId) {
        transactions.transactional(
                outbox.append(envelope("SettlementHeld", app, reason, taskOwnerId))
                        .then(opsCases.register(OpsCaseSource.SETTLEMENT_HELD, app.id(),
                                input.organizationId(), app.id(), reason))).block();
        log.warn("settlement held org={} app={} reason={}", input.organizationId(), app.id(), reason);
        return SettlementOutcome.held(reason);
    }

    private EventEnvelope envelope(String eventType, TaskApplication app, String reason, String taskOwnerId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", app.taskId());
        payload.put("applicationId", app.id());
        payload.put("recommenderAccountId", app.recommenderAccountId());
        payload.put("status", app.status());
        if (reason != null) {
            payload.put("reason", reason);
        }
        if (taskOwnerId != null) {
            payload.put("taskOwnerId", taskOwnerId);
        }
        String eventId = UUID.nameUUIDFromBytes(
                (eventType + ":" + app.id()).getBytes(StandardCharsets.UTF_8)).toString();
        return new EventEnvelope(eventId, eventType, "TaskApplication",
                app.id(), 1, Instant.now(), null, payload);
    }
}
