package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.ops.OpsCaseRegistrar;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 草稿审稿超时派发器（任务书 #96 C96-04 / TC96-016）：商家审稿窗口（默认 72h 可配）到期未批/退 →
 * 提醒事件（确定性 eventId，exactly-once）+ 转人工（ops_case，UNIQUE(source,ref) 幂等）。
 * 审稿超时动作缺省<b>转人工</b>（§13 待拍板项：自动通过 vs 转人工的缺省方向，按转人工落地，配置可调政策后续卡扩展）。
 * 待审草稿行是 durable intent；行级守卫（status 仍 submitted）保证批/退先到时扫描为空。
 */
@Component
@ConditionalOnProperty(prefix = "marketplace.engagement", name = "draft-review-dispatcher-enabled",
        havingValue = "true", matchIfMissing = true)
public class DraftReviewDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DraftReviewDispatcher.class);

    private final SubmissionRepository submissions;
    private final TaskApplicationRepository apps;
    private final OutboxRepository outbox;
    private final OpsCaseRegistrar opsCases;
    private final int batchSize;
    private final long reviewWindowSeconds;

    public DraftReviewDispatcher(SubmissionRepository submissions,
            TaskApplicationRepository apps, OutboxRepository outbox, OpsCaseRegistrar opsCases,
            @Value("${marketplace.engagement.draft-review-dispatcher-batch-size:32}") int batchSize,
            @Value("${marketplace.engagement.review-window-hours:72}") long reviewWindowHours) {
        this.submissions = submissions;
        this.apps = apps;
        this.outbox = outbox;
        this.opsCases = opsCases;
        this.batchSize = Math.max(1, batchSize);
        this.reviewWindowSeconds = Math.max(1, reviewWindowHours * 3600L);
    }

    @Scheduled(fixedDelayString = "${marketplace.engagement.draft-review-dispatcher-poll-ms:5000}")
    public void dispatch() {
        Mono.fromRunnable(this::dispatchBatch)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    void dispatchBatch() {
        List<EngagementSubmission> overdue = submissions.findDraftReviewOverdue(batchSize, reviewWindowSeconds)
                .collectList().block();
        if (overdue == null) {
            return;
        }
        for (EngagementSubmission draft : overdue) {
            try {
                escalate(draft);
            } catch (RuntimeException failure) {
                log.warn("draft review escalation failed submission={} app={}", draft.id(), draft.applicationId(),
                        failure);
            }
        }
    }

    private void escalate(EngagementSubmission draft) {
        TaskApplication app = apps.findById(draft.applicationId()).block();
        if (app == null || !"accepted".equals(app.status())) {
            return;
        }
        outbox.append(reminderEnvelope(app, draft)).block();
        // 转人工：审稿超时处置单（幂等：UNIQUE(source_kind, source_ref)）
        opsCases.register("draft_review_timeout", draft.id(), null, app.id(), "draft_review_timeout").block();
    }

    private EventEnvelope reminderEnvelope(TaskApplication app, EngagementSubmission draft) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", app.taskId());
        payload.put("applicationId", app.id());
        payload.put("recommenderAccountId", app.recommenderAccountId());
        payload.put("submissionId", draft.id());
        payload.put("reason", "draft_review_timeout");
        String eventId = UUID.nameUUIDFromBytes(
                ("DraftReviewExpiring:" + draft.id()).getBytes(StandardCharsets.UTF_8)).toString();
        return new EventEnvelope(eventId, "DraftReviewExpiring", "EngagementSubmission",
                draft.id(), 1, Instant.now(), null, payload);
    }
}
