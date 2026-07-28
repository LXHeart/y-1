package com.grassland.marketplace.workflow.saga;

import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.settlement.SettlementReconciliation;
import com.grassland.marketplace.settlement.SettlementReconciliationRepository;
import com.grassland.marketplace.taskcatalog.Task;
import com.grassland.marketplace.taskcatalog.TaskApplication;
import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
import com.grassland.marketplace.taskcatalog.TaskRepository;
import com.grassland.marketplace.workflow.FinanceReconciliationClient;
import com.grassland.marketplace.workflow.TrustResolutionClient;
import io.temporal.spring.boot.ActivityImpl;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;

/**
 * 结算对账 activity 实现（Slice 7B）。争议终局后：重读 trust 权威终局 + finance 权威预留状态，
 * 幂等补执行缺失的钱动作，确认后才发布 EngagementSettled。每次重试幂等。
 *
 * <p>关键不变量：**永不**在 finance 未确认 desired 状态时写 EngagementSettled。业务阻断（trust 不一致 /
 * finance blocked/conflict/missing）→ 写 SettlementReconciliationBlocked + 持久 blocked，结算态保持 held。
 * 网络/5xx 错误抛出交 Temporal 重试（不持久化阻断，依赖恢复后自愈）。
 */
@Component
@ActivityImpl(workers = ApplicationReservationWorkflowImpl.TASK_QUEUE)
public class SettlementReconciliationActivityImpl implements SettlementReconciliationActivity {

    private static final Logger log = LoggerFactory.getLogger(SettlementReconciliationActivityImpl.class);

    private final SettlementReconciliationRepository reconciliations;
    private final TaskApplicationRepository apps;
    private final TaskRepository tasks;
    private final OutboxRepository outbox;
    private final TrustResolutionClient trust;
    private final FinanceReconciliationClient finance;
    private final TransactionalOperator transactions;

    public SettlementReconciliationActivityImpl(
            SettlementReconciliationRepository reconciliations,
            TaskApplicationRepository apps,
            TaskRepository tasks,
            OutboxRepository outbox,
            TrustResolutionClient trust,
            FinanceReconciliationClient finance,
            TransactionalOperator transactions) {
        this.reconciliations = reconciliations;
        this.apps = apps;
        this.tasks = tasks;
        this.outbox = outbox;
        this.trust = trust;
        this.finance = finance;
        this.transactions = transactions;
    }

    @Override
    public SettlementReconciliationWorkflow.ReconciliationOutcome reconcile(
            SettlementReconciliationWorkflow.ReconciliationInput input) {
        SettlementReconciliation rec = reconciliations.findBySourceEventId(input.sourceEventId()).block();
        if (rec == null || rec.isTerminal()) {
            return SettlementReconciliationWorkflow.ReconciliationOutcome.reconciled();  // 幂等：已对账/未知
        }
        TaskApplication app = apps.findById(input.applicationId()).block();
        if (app == null || !"accepted".equals(app.status())) {
            return block(input, "application_not_accepted");
        }
        if (app.confirmedAt() == null) {
            return block(input, "application_not_confirmed");
        }
        Task task = tasks.findById(app.taskId()).block();
        if (task == null) {
            return block(input, "task_missing");
        }
        String organizationId = task.organizationId();

        // 1. trust 权威终局：须 final + 全字段一致。transport 错误抛出→重试；内容不一致→持久阻断。
        TrustResolutionClient.TrustResolution resolution =
                trust.resolve(organizationId, input.disputeId()).block();
        if (resolution == null
                || !"final".equals(resolution.status())
                || !Objects.equals(resolution.disputeId(), input.disputeId())
                || !Objects.equals(resolution.engagementRef(), input.applicationId())
                || !Objects.equals(resolution.organizationId(), organizationId)
                || !Objects.equals(resolution.finalDecision(), input.finalDecision())) {
            return block(input, "trust_mismatch");
        }

        // 2. 非资金型任务（bounty null/0）：无 reservation 期望，直接结算。
        if (task.bountyCents() == null || task.bountyCents() <= 0) {
            return complete(input, app, input.finalDecision());
        }

        // 3. finance 权威对账：verified/repaired→成功；blocked/conflict/missing→持久阻断；transport 错误→重试。
        FinanceReconciliationClient.Result result =
                finance.reconcile(organizationId, input.applicationId(), input.finalDecision()).block();
        if (result == null || !result.isSuccess()) {
            String reason = result == null ? "finance_missing" : "finance_" + result.outcome();
            return block(input, reason);
        }
        return complete(input, app, input.finalDecision());
    }

    /** 成功：同事务 markReconciled + 确定性 EngagementSettled（重试不重复）。 */
    private SettlementReconciliationWorkflow.ReconciliationOutcome complete(
            SettlementReconciliationWorkflow.ReconciliationInput input, TaskApplication app, String decision) {
        transactions.transactional(reconciliations
                .markReconciled(input.sourceEventId())
                .then(outbox.append(settledEnvelope(input, app, decision)))).block();
        log.info("settlement reconciled src={} app={} decision={}", input.sourceEventId(), app.id(), decision);
        return SettlementReconciliationWorkflow.ReconciliationOutcome.reconciled();
    }

    /** 阻断：同事务 markBlocked + 确定性 SettlementReconciliationBlocked（运维可见，永不写 EngagementSettled）。 */
    private SettlementReconciliationWorkflow.ReconciliationOutcome block(
            SettlementReconciliationWorkflow.ReconciliationInput input, String reason) {
        transactions.transactional(reconciliations
                .markBlocked(input.sourceEventId(), reason)
                .then(outbox.append(blockedEnvelope(input, reason)))).block();
        log.warn("settlement blocked src={} reason={}", input.sourceEventId(), reason);
        return SettlementReconciliationWorkflow.ReconciliationOutcome.blocked(reason);
    }

    private EventEnvelope settledEnvelope(
            SettlementReconciliationWorkflow.ReconciliationInput input, TaskApplication app, String decision) {
        String reason = decision == null || decision.isBlank() ? "adjudication" : "adjudication:" + decision;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("applicationId", app.id());
        payload.put("reason", reason);
        return derivedEnvelope(input, "EngagementSettled", payload);
    }

    private EventEnvelope blockedEnvelope(
            SettlementReconciliationWorkflow.ReconciliationInput input, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("applicationId", input.applicationId());
        payload.put("reason", reason);
        return derivedEnvelope(input, "SettlementReconciliationBlocked", payload);
    }

    private EventEnvelope derivedEnvelope(
            SettlementReconciliationWorkflow.ReconciliationInput input, String eventType, Map<String, Object> payload) {
        String eventId = UUID.nameUUIDFromBytes(
                (eventType + ":" + input.sourceEventId()).getBytes(StandardCharsets.UTF_8)).toString();
        return new EventEnvelope(eventId, eventType, "TaskApplication",
                input.applicationId(), 1, Instant.now(), input.sourceEventId(), payload);
    }
}
