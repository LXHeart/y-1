package com.grassland.marketplace.workflow.saga;

import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.ops.OpsCaseRegistrar;
import com.grassland.marketplace.ops.OpsCaseSource;
import com.grassland.marketplace.taskcatalog.TaskApplication;
import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
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
 * 结算执行（gate + capture + hold）——抽自 {@code SettlementActivityImpl} 的共享钱侧逻辑（D-03）。
 *
 * <p>商家手动确认（{@link SettlementActivityImpl}）与窗口到期自动结算（{@link ConfirmationActivityImpl}）
 * 共用同一套：查争议（{@link DisputeChecker}）→ 查核验（{@link VerificationChecker}）→ finance capture +
 * outbox {@code EngagementSettled}，或 hold（outbox {@code SettlementHeld} + ops_case 同事务）。避免两路
 * capture 逻辑分叉（钱侧正确性，必须单一真相源）。
 *
 * <p>调用方负责上游重验（accepted / confirmed_at）与本方法的衔接；本方法不再检查 confirmed_at。
 * outbox 用确定性 event_id（type-3 UUID）保 activity 重试 exactly-once。
 */
@Component
public class SettlementExecution {

    private static final Logger log = LoggerFactory.getLogger(SettlementExecution.class);

    private final OutboxRepository outbox;
    private final TaskApplicationRepository applications;
    private final FinanceEscrowClient finance;
    private final DisputeChecker disputes;
    private final VerificationChecker verification;
    private final OpsCaseRegistrar opsCases;
    private final TransactionalOperator transactions;

    public SettlementExecution(OutboxRepository outbox, TaskApplicationRepository applications,
                               FinanceEscrowClient finance, DisputeChecker disputes,
                               VerificationChecker verification, OpsCaseRegistrar opsCases,
                               TransactionalOperator transactions) {
        this.outbox = outbox;
        this.applications = applications;
        this.finance = finance;
        this.disputes = disputes;
        this.verification = verification;
        this.opsCases = opsCases;
        this.transactions = transactions;
    }

    /**
     * 执行结算 capture（带 gate）：开放争议 → hold({@code open_dispute})；核验 failed → hold({@code verification_failed})；
     * 否则 finance capture + outbox {@code EngagementSettled} → settled。{@code taskOwnerId} 为通知收件人（任务缺失则 null）。
     */
    public SettlementOutcome captureOrHold(String organizationId, String applicationId,
                                           TaskApplication app, String taskOwnerId) {
        // F6 本地最后门闩：controller/Timer 上游重验之后仍可能发生 contest；capture 前必须重新读 marketplace 权威行。
        // 一旦 claim 已提交，即使 trust 案尚未创建或远端读尚不可见，也只能 hold，绝不能调用 finance。
        TaskApplication fresh = applications.findById(applicationId).block();
        if (fresh == null) {
            return SettlementOutcome.aborted();
        }
        if (fresh.contestRequestedAt() != null) {
            return hold(organizationId, applicationId, fresh, "merchant_contest_requested", taskOwnerId);
        }
        if (disputes.hasOpenDispute(organizationId, applicationId)) {
            return hold(organizationId, applicationId, app, "open_dispute", taskOwnerId);  // HLD 16：结算前重查 Hold
        }
        // 履约核验安全网闸门（Verification v1）：与争议闸门正交，failed 核验 → hold（inconclusive/passed/无记录不阻断）。
        if (verification.blocksSettlement(organizationId, applicationId)) {
            return hold(organizationId, applicationId, app, "verification_failed", taskOwnerId);
        }
        log.info("settlement capture START org={} ref={}", organizationId, applicationId);
        // finance reserved→captured；409(已终态) 由 client 映射为成功（幂等）；真异常抛出→Temporal 重试
        finance.capture(organizationId, applicationId).block();
        outbox.append(envelope("EngagementSettled", app, null, taskOwnerId)).block();
        return SettlementOutcome.settled();
    }

    /**
     * 暂缓：outbox {@code SettlementHeld} + 运营处置单登记，**同一事务**（GL-P1-OPS-001 Stage 1）。
     *
     * <p>两者都幂等（outbox 用确定性 event_id，ops_case 用 {@code UNIQUE(source_kind, source_ref)}），
     * 故 activity 重跑安全。sourceRef 取 {@code applicationId}——同一笔履约的反复暂缓归并到一张单。
     */
    private SettlementOutcome hold(String organizationId, String applicationId,
                                   TaskApplication app, String reason, String taskOwnerId) {
        transactions.transactional(
                outbox.append(envelope("SettlementHeld", app, reason, taskOwnerId))
                        .then(opsCases.register(OpsCaseSource.SETTLEMENT_HELD, app.id(),
                                organizationId, applicationId, reason))).block();
        log.warn("settlement held org={} app={} reason={}", organizationId, applicationId, reason);
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
