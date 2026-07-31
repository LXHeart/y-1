package com.grassland.marketplace.workflow.saga;

import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.taskcatalog.TaskApplication;
import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
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

/**
 * 结算窗口 Saga 活动实现（草场 Epic 5 Slice 5A / HLD 9.2、10.3）。
 *
 * <p>窗口到期后执行（幂等 + 重验）：重验 accepted+confirmed → 查争议（{@link DisputeChecker} seam）→
 * 无争议则 finance capture（reserved→captured）+ outbox {@code EngagementSettled}；有争议则 outbox
 * {@code SettlementHeld}。outbox 用确定性 event_id（type-3 UUID）保重试 exactly-once。
 */
@Component
@ActivityImpl(workers = ApplicationReservationWorkflowImpl.TASK_QUEUE)
public class SettlementActivityImpl implements SettlementActivity {

    private static final Logger log = LoggerFactory.getLogger(SettlementActivityImpl.class);

    private final TaskApplicationRepository apps;
    private final OutboxRepository outbox;
    private final FinanceEscrowClient finance;
    private final DisputeChecker disputes;
    private final VerificationChecker verification;

    public SettlementActivityImpl(TaskApplicationRepository apps, OutboxRepository outbox,
                                  FinanceEscrowClient finance, DisputeChecker disputes,
                                  VerificationChecker verification) {
        this.apps = apps;
        this.outbox = outbox;
        this.finance = finance;
        this.disputes = disputes;
        this.verification = verification;
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
        if (disputes.hasOpenDispute(input.organizationId(), input.applicationId())) {
            outbox.append(envelope("SettlementHeld", app, "open_dispute")).block();
            return SettlementOutcome.held("open_dispute");  // HLD 16：结算执行前重新检查 Hold
        }
        // 履约核验安全网闸门（Verification v1）：与争议闸门正交，failed 核验 → hold（inconclusive/passed/无记录不阻断）。
        if (verification.blocksSettlement(input.organizationId(), input.applicationId())) {
            outbox.append(envelope("SettlementHeld", app, "verification_failed")).block();
            return SettlementOutcome.held("verification_failed");
        }
        log.info("settlement capture START org={} ref={}", input.organizationId(), input.applicationId());
        // finance reserved→captured；409(已终态 captured/released) 由 client 映射为成功（幂等）；真异常抛出→Temporal 重试
        finance.capture(input.organizationId(), input.applicationId()).block();
        outbox.append(envelope("EngagementSettled", app, null)).block();
        return SettlementOutcome.settled();
    }

    private EventEnvelope envelope(String eventType, TaskApplication app, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", app.taskId());
        payload.put("applicationId", app.id());
        payload.put("recommenderAccountId", app.recommenderAccountId());
        payload.put("status", app.status());
        if (reason != null) {
            payload.put("reason", reason);
        }
        String eventId = UUID.nameUUIDFromBytes(
                (eventType + ":" + app.id()).getBytes(StandardCharsets.UTF_8)).toString();
        return new EventEnvelope(eventId, eventType, "TaskApplication",
                app.id(), 1, Instant.now(), null, payload);
    }
}
