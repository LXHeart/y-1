package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.workflow.saga.MerchantContestCoordinator;
import com.grassland.marketplace.workflow.saga.SettlementWorkflowStarter;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 履约收尾决策（D-02/D-03 + Slice 7B）：商家确认（含阶梯佣金申报指标）、转客服争议、
 * 结算/确认窗口结局轮询、履约评分。资源级自查（caller==task.owner）与已接受态守卫由控制器完成后传入。
 */
@Component
public class EngagementDecisionService {

    private final TaskApplicationRepository apps;
    private final SubmissionRepository submissions;
    private final EngagementVerificationRepository verifications;
    private final RatingRepository ratings;
    private final MerchantContestCoordinator contests;
    private final com.grassland.marketplace.settlement.SettlementReconciliationRepository reconciliations;
    private final SettlementWorkflowStarter settlementWorkflows;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;

    public EngagementDecisionService(TaskApplicationRepository apps,
                                     SubmissionRepository submissions,
                                     EngagementVerificationRepository verifications,
                                     RatingRepository ratings,
                                     MerchantContestCoordinator contests,
                                     com.grassland.marketplace.settlement.SettlementReconciliationRepository reconciliations,
                                     SettlementWorkflowStarter settlementWorkflows,
                                     OutboxRepository outbox,
                                     TransactionalOperator transactions) {
        this.apps = apps;
        this.submissions = submissions;
        this.verifications = verifications;
        this.ratings = ratings;
        this.contests = contests;
        this.reconciliations = reconciliations;
        this.settlementWorkflows = settlementWorkflows;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    /**
     * 商家确认履约 → 启结算窗口。
     *
     * <p><b>必须先有待核验的交付物</b>：此前 confirm 是凭空点的——推荐官交了什么、商家在确认什么，
     * 系统里没有任何记录。现在确认即等于「核验通过这份交付物」，同时把它置为 accepted。
     */
    public Mono<ResponseEntity<Map<String, Object>>> confirm(Task task, TaskApplication app,
                                                             ConfirmEngagementRequest body) {
        // contest claim 一旦落地即不允许普通确认；不必等待 trust 案/merchant_rejected_at 完成。
        return app.contestRequestedAt() != null
                ? fail(409, "已发起争议，无法确认")
                // 已由商家/自动路径确认 → 用确定性 workflow id 补启/收敛后幂等 200。
                : app.confirmedAt() != null
                        ? resumeConfirmedSettlement(task, app)
                        : requiredDeclaredMetric(app, body)
                        .flatMap(metric -> transactions.transactional(
                                confirmWork(app.taskId(), app.id(), task, metric.orElse(null)))
                        .flatMap(confirmed -> startSettlementWorkflow(task, confirmed))
                        // 商家确认与 auto-confirm 竞态：事务内 guarded write 失败会抛标记异常并回滚；
                        // 回读若已确认则幂等 200，否则按原业务错误返回 409。
                        .onErrorResume(ConfirmationConflict.class, conflict -> apps.findById(app.id())
                                .filter(current -> "accepted".equals(current.status())
                                        && current.confirmedAt() != null)
                                .flatMap(current -> resumeConfirmedSettlement(task, current))
                                .switchIfEmpty(fail(409, conflict.getMessage()))));
    }

    /**
     * D-02：阶梯佣金任务的手动确认必须携带商家申报的指标达成值（Sandbox 指标事实来源）；
     * 固定佣金契约（快照无 ladder）无此要求。快照解析失败按损坏处理，阻断确认。
     */
    private Mono<Optional<Long>> requiredDeclaredMetric(TaskApplication app, ConfirmEngagementRequest body) {
        return apps.findTaskContextSnapshot(app.id())
                .flatMap(snapshot -> Mono.justOrEmpty(CommissionLadders.fromTaskContextSnapshot(snapshot)))
                .flatMap(ladder -> {
                    Long declared = body == null ? null : body.confirmedMetricValue();
                    if (declared == null) {
                        return Mono.error(new MarketplaceException(400,
                                "阶梯佣金任务确认时必须申报指标达成值（confirmedMetricValue）"));
                    }
                    if (declared < 0) {
                        return Mono.error(new MarketplaceException(400, "申报指标值不能为负数"));
                    }
                    return Mono.just(Optional.of(declared));
                })
                .onErrorMap(IllegalArgumentException.class, error ->
                        new MarketplaceException(409, error.getMessage()))
                .defaultIfEmpty(Optional.empty());
    }

    /** 手动确认领域写：submission accepted + application confirmed（含 D-02 申报指标值）+ MerchantConfirmed outbox，同一事务。 */
    private Mono<TaskApplication> confirmWork(String taskId, String appId, Task task, Long confirmedMetricValue) {
        return submissions.findPending(appId)
                .switchIfEmpty(Mono.error(new ConfirmationConflict("推荐官尚未提交履约凭证，无法确认")))
                .flatMap(pending -> verifications.findEffectiveStatus(pending.id())
                        .map(status -> "failed".equalsIgnoreCase(status))
                        .defaultIfEmpty(false)
                        .flatMap(blocked -> {
                            // 可选闸门：仅 failed 阻断 confirm（absent/passed/inconclusive 照常）。
                            // GL-P2-ADMIN-004：生效状态含人工改判 override（override 优先于自动结论）。
                            if (Boolean.TRUE.equals(blocked)) {
                                return Mono.<EngagementSubmission>error(new MarketplaceException(
                                        409, "履约核验未通过，请退回重交或重新核验"));
                            }
                            return Mono.just(pending);
                        }))
                .flatMap(pending -> submissions.review(pending.id(), SubmissionStatus.ACCEPTED, null))
                .switchIfEmpty(Mono.error(new ConfirmationConflict("该交付物已处理")))
                .flatMap(acceptedSubmission -> apps.confirm(appId, taskId, confirmedMetricValue)
                        .switchIfEmpty(Mono.error(new ConfirmationConflict("该报名未接受或已确认"))))
                .flatMap(confirmed -> outbox
                        .append(ApplicationEvents.envelope("MerchantConfirmed", confirmed, task.ownerAccountId()))
                        .thenReturn(confirmed));
    }

    private Mono<ResponseEntity<Map<String, Object>>> resumeConfirmedSettlement(
            Task task, TaskApplication application) {
        return settlementWorkflows.start(task, application)
                .thenReturn(ApplicationBodies.confirmedResponse(application));
    }

    /** 资金型任务已确认 → 启结算窗口 Saga：202 settling（Timer 后 capture）。双击去重。 */
    private Mono<ResponseEntity<Map<String, Object>>> startSettlementWorkflow(Task task, TaskApplication app) {
        return settlementWorkflows.start(task, app)
        .map(wid -> ResponseEntity.status(org.springframework.http.HttpStatus.ACCEPTED).body(Map.of("success", true, "data",
                Map.of("workflowId", wid, "applicationId", app.id(), "status", "settling"))));
    }

    /**
     * 商家拒绝「系统核实通过」的履约（D-03 §2）→ 先取得本地 durable contest 门闩，再转客服终审。
     * 门闩与 manual/auto confirm 更新同一 application 行；一旦提交，Timer 即使尚未读到 trust 案也不得 capture。
     */
    public Mono<ResponseEntity<Map<String, Object>>> contest(Task task, TaskApplication app, String reason) {
        if (app.contestRequestedAt() != null) {
            // durable claim 已落：重复请求继续未完成的 trust/本地/SLA 步骤。
            return contests.dispatch(app, task).map(ApplicationBodies::contestedResponse);
        }
        if (app.confirmedAt() != null) {
            return fail(409, "该履约已确认，不能再发起拒绝");
        }
        if (app.merchantConfirmDeadlineAt() == null
                || !app.merchantConfirmDeadlineAt().isAfter(Instant.now())) {
            return fail(409, "商家确认窗口已到期，不能再发起拒绝");
        }
        return submissions.findPending(app.id())
                .switchIfEmpty(fail(409, "无待确认的履约凭证"))
                .flatMap(submission -> verifications.findBySubmission(submission.id())
                        .filter(v -> "passed".equalsIgnoreCase(v.status()))
                        .switchIfEmpty(fail(409, "仅系统核实通过的履约可转客服拒绝"))
                        // claim 自身单独提交后才允许任何远端动作；与 Timer 的 guarded update 决胜。
                        .then(transactions.transactional(
                                apps.claimContest(app.id(), task.id(), reason)
                                        .switchIfEmpty(fail(409, "该履约状态已变"))))
                        .then(Mono.defer(() -> apps.findById(app.id())))
                        .flatMap(claimed -> contests.dispatch(claimed, task))
                        .map(ApplicationBodies::contestedResponse));
    }

    /**
     * 轮询商家确认窗口状态（D-03）：返回 {@code status} + {@code deadline} + {@code remainingSeconds}（估算展示）。
     * <ul>
     *   <li>{@code confirmed_at} 已设 → {@code confirmed}（已确认，结算进行中/已完成，细节走 settlement 轮询）。</li>
     *   <li>未确认 + 有 deadline → {@code awaiting_confirmation} + 倒计时（真正到期由 Temporal Timer 驱动，估算不作判定）。</li>
     *   <li>未确认 + 无 deadline → {@code not_entered}（未提交履约）。</li>
     * </ul>
     */
    public ResponseEntity<Map<String, Object>> confirmationOutcome(TaskApplication app) {
        if (app.contestRequestedAt() != null && app.merchantRejectedAt() == null) {
            return ApplicationBodies.ok(Map.of("applicationId", app.id(), "status", "contest_pending", "reason",
                    app.rejectionReason() == null ? "" : app.rejectionReason()));
        }
        if (app.merchantRejectedAt() != null) {
            return ApplicationBodies.contestedResponse(app);
        }
        if (app.confirmedAt() != null) {
            return ApplicationBodies.ok(Map.of("status", "confirmed"));
        }
        if (app.merchantConfirmDeadlineAt() == null) {
            return ApplicationBodies.ok(Map.of("status", "not_entered"));
        }
        long remainingSeconds = Math.max(0,
                java.time.Duration.between(Instant.now(), app.merchantConfirmDeadlineAt()).toSeconds());
        return ApplicationBodies.ok(Map.of(
                "status", "awaiting_confirmation",
                "deadline", app.merchantConfirmDeadlineAt().toString(),
                "remainingSeconds", remainingSeconds));
    }

    /**
     * 映射 application 为结算结局（Slice 7B 优先对账行）：
     * <ul>
     *   <li>未确认 → not_confirmed；</li>
     *   <li>有对账行：reconciled→settled（钱已确认到位）、blocked/pending/started→held（reason 区分阻断/进行中）；</li>
     *   <li>无对账行（无争议的正常结算）→ 回退 outbox 的 EngagementSettled/SettlementHeld，否则 settling。</li>
     * </ul>
     * 争议结算必须经对账确认才显 settled——避免「争议终局≠钱已到位」时误报已结算。
     */
    public Mono<ResponseEntity<Map<String, Object>>> settlementOutcome(TaskApplication app) {
        if (app.merchantRejectedAt() != null) {
            return reconciliations.findLatestForApplication(app.id())
                    .map(this::reconciliationOutcome)
                    .switchIfEmpty(Mono.just(ApplicationBodies.contestedResponse(app)));
        }
        if (app.confirmedAt() == null) {
            return Mono.just(ApplicationBodies.ok(Map.of("status", "not_confirmed")));
        }
        return reconciliations.findLatestForApplication(app.id())
                .map(this::reconciliationOutcome)
                .switchIfEmpty(outbox.latestSettlementStatus(app.id())
                        .map(ApplicationBodies::ok)
                        .defaultIfEmpty(ApplicationBodies.ok(Map.of("status", "settling"))));
    }

    private ResponseEntity<Map<String, Object>> reconciliationOutcome(
            com.grassland.marketplace.settlement.SettlementReconciliation rec) {
        return switch (rec.status()) {
            case "reconciled" -> {
                String reason = rec.finalDecision() == null || rec.finalDecision().isBlank()
                        ? "adjudication" : "adjudication:" + rec.finalDecision();
                yield ApplicationBodies.ok(Map.of("status", "settled", "reason", reason));
            }
            case "blocked" -> ApplicationBodies.ok(Map.of("status", "held",
                    "reason", rec.reason() == null ? "blocked" : rec.reason()));
            default -> ApplicationBodies.ok(Map.of("status", "held", "reason", "reconciliation_pending"));
        };
    }

    /**
     * 商家给推荐官打分（PRD 五：等级门槛全部依赖评分）。一次履约只评一份（DB UNIQUE → 409），
     * 商家不能反复改分刷高/压低某个推荐官；outbox {@code EngagementRated} 供声誉/风控消费。
     */
    public Mono<EngagementRating> rate(TaskApplication app, Caller merchant, RateEngagementRequest body) {
        return ratings.create(app.id(), app.taskId(), app.recommenderAccountId(),
                        merchant.accountId(), body.score(), body.comment())
                .switchIfEmpty(fail(409, "该履约已评价过"))
                .flatMap(rating -> outbox
                        .append(ApplicationEvents.ratingEnvelope(app, rating))
                        .thenReturn(rating));
    }

    /** 查该履约的评分；未评价 → 空（控制器映射 {@code data: null}，不是 404）。 */
    public Mono<EngagementRating> rating(String appId) {
        return ratings.findByApplication(appId);
    }

    /** 仅用于手动确认与 auto-confirm 并发：抛出即触发 R2DBC 事务 rollback，外层再回读确认态。 */
    private static final class ConfirmationConflict extends RuntimeException {
        private ConfirmationConflict(String message) {
            super(message);
        }
    }

    private static <T> Mono<T> fail(int status, String message) {
        return Mono.error(new MarketplaceException(status, message));
    }
}
