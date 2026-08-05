package com.grassland.trust.dispute;

import com.grassland.trust.audit.DisputeAuditRepository;
import com.grassland.trust.event.EventEnvelope;
import com.grassland.trust.event.OutboxRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 证据提交编排（GL-P2-TRUST-001 T1）。开争议带证据 + 争议开启后补证据共用此路径。
 *
 * <p>单次提交（可含多条证据项）在同一事务内完成：<b>逐条 append dispute_evidence + 首次提交点亮 evidence_ref
 * + 逐条 append DisputeEvidenceSubmitted outbox + append dispute_audit(evidence_submitted)</b>。
 * 任一失败整体回滚，避免「证据落了但事件没发」或「事件发了但句柄没点亮」。
 *
 * <p><b>outbox payload 刻意不含 raw 证据内容</b>（D-10：证据内容是受限对象，不进事件流）——只带
 * disputeId / evidenceId / kind / 提交人账号 + 角色，供下游通知中心解析收件人。
 */
@Component
public class DisputeEvidenceService {

    private final DisputeEvidenceRepository evidenceRepo;
    private final DisputeCaseRepository disputes;
    private final OutboxRepository outbox;
    private final DisputeAuditRepository audit;
    private final EvidenceProperties evidenceProps;
    private final TransactionalOperator transactions;

    public DisputeEvidenceService(DisputeEvidenceRepository evidenceRepo, DisputeCaseRepository disputes,
                                  OutboxRepository outbox, DisputeAuditRepository audit,
                                  EvidenceProperties evidenceProps, TransactionalOperator transactions) {
        this.evidenceRepo = evidenceRepo;
        this.disputes = disputes;
        this.outbox = outbox;
        this.audit = audit;
        this.evidenceProps = evidenceProps;
        this.transactions = transactions;
    }

    /**
     * 向争议追加证据项。{@code items} 为空 → no-op（返回空列表，不写句柄/事件/审计）。
     * 返回落库后的证据（含 DB 生成的 created_at）。
     */
    public Mono<List<DisputeEvidence>> submit(String disputeId, String submitterAccountId, String submitterRole,
                                              List<OpenDisputeRequest.EvidenceItem> items) {
        if (items == null || items.isEmpty()) {
            return Mono.just(List.of());
        }
        Instant retentionUntil = Instant.now().plus(Duration.ofDays(evidenceProps.retentionDays()));
        return transactions.transactional(
                Flux.fromIterable(items)
                        .concatMap(item -> evidenceRepo.append(new DisputeEvidence(
                                UUID.randomUUID().toString(), disputeId, submitterAccountId, submitterRole,
                                item.kind(), item.contentRef(), null, item.caption(), null, retentionUntil)))
                        .collectList()
                        .flatMap(saved -> disputes.updateEvidenceRef(disputeId, "set:" + disputeId)
                                .then(audit.append(disputeId, "evidence_submitted", submitterAccountId, submitterRole,
                                        "提交 " + saved.size() + " 条证据"))
                                .then(appendEvidenceEvents(saved))
                                .thenReturn(saved)));
    }

    /** 逐条 append DisputeEvidenceSubmitted outbox（幂等 eventId = evidence:disputeId:evidenceId）。 */
    private Mono<Void> appendEvidenceEvents(List<DisputeEvidence> saved) {
        return Flux.fromIterable(saved).concatMap(e -> outbox.append(evidenceEnvelope(e))).then();
    }

    private EventEnvelope evidenceEnvelope(DisputeEvidence e) {
        String eventId = UUID.nameUUIDFromBytes(
                ("DisputeEvidenceSubmitted:" + e.disputeId() + ":" + e.id()).getBytes(StandardCharsets.UTF_8)).toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("disputeId", e.disputeId());
        payload.put("evidenceId", e.id());
        payload.put("kind", e.kind());
        payload.put("submittedByAccountId", e.submittedByAccountId());
        payload.put("submittedByRole", e.submittedByRole());
        // 刻意不带 contentRef/redactedRef（D-10：证据 raw 不进事件流）
        return new EventEnvelope(eventId, "DisputeEvidenceSubmitted", "DisputeCase",
                e.disputeId(), 0, Instant.now(), null, payload);
    }
}
