package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.matching.TaskRecommenderInvitationRepository;
import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.reputation.ReputationService;
import com.grassland.marketplace.reputation.ReputationSnapshot;
import com.grassland.marketplace.workflow.saga.MerchantContestCoordinator;
import com.grassland.marketplace.workflow.saga.SettlementWorkflowStarter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * application 聚合 HTTP 入口（草场 Epic 4 Slice 4B / HLD 5.3、10.2；4F 资金预留 Saga 接线）。
 *
 * <ul>
 *   <li>POST /api/tasks/{id}/applications — 推荐官报名（requireRecommender；任务须 published；名额满 fail-fast 409；
 *       一人一报 409；outbox {@code ApplicationSubmitted}）。</li>
 *   <li>POST /api/tasks/{id}/applications/{appId}/accept — 商家接受（须任务 owner）。接受命令、原子名额占用、
 *       状态迁移和 outbox 在同一事务内完成；资金型任务由 durable dispatcher 启动预留 Saga，支持显式
 *       {@code Idempotency-Key} 重放和冲突检测。</li>
 *   <li>GET /api/tasks/{id}/applications/{appId}/reservation — owner 轮询预留结局
 *       （accepted/reserving/compensated+reason；#26 D12 附 {@code taskClosed}）。</li>
 *   <li>POST .../reject — 商家拒绝（须任务 owner；outbox {@code ApplicationRejected}）。</li>
 *   <li>POST .../withdraw — 推荐官撤销本人 pending（outbox {@code ApplicationWithdrawn}）。</li>
 *   <li>GET /api/tasks/{id}/applications — 任务 owner 列全部报名。</li>
 * </ul>
 *
 * <p>身份靠 {@link MarketplaceCallerResolver}（BFF 断言）；资源级自查：accept/reject/reservation 校验 caller==task.owner，
 * withdraw 把 recommender 烧进 WHERE（HLD 7.4）。阻塞的 WorkflowClient 调用包 {@code boundedElastic} 避免卡事件循环。
 */
@RestController
public class ApplicationController {

    private static final Logger log = LoggerFactory.getLogger(ApplicationController.class);

    private final MarketplaceCallerResolver callers;
    private final TaskRepository tasks;
    private final TaskResourceAuthorization taskAuthorization;
    private final TaskApplicationRepository apps;
    private final TaskMetricsRepository metrics;
    private final TaskAcceptanceCounterRepository acceptanceCounters;
    private final ApplicationAcceptanceService acceptance;
    private final OutboxRepository outbox;
    private final SubmissionRepository submissions;
    private final EngagementSubmissionService submissionService;
    private final EngagementVerificationService verificationService;
    private final EngagementVerificationRepository verifications;
    private final RatingRepository ratings;
    private final MerchantContestCoordinator contests;
    private final com.grassland.marketplace.settlement.SettlementReconciliationRepository reconciliations;
    private final SettlementWorkflowStarter settlementWorkflows;
    private final ReputationService reputationService;
    private final TaskRecommenderInvitationRepository recommenderInvitations;
    private final TransactionalOperator transactions;

    public ApplicationController(MarketplaceCallerResolver callers, TaskRepository tasks,
                                 TaskResourceAuthorization taskAuthorization,
                                 TaskApplicationRepository apps,
                                 TaskMetricsRepository metrics,
                                 TaskAcceptanceCounterRepository acceptanceCounters,
                                 ApplicationAcceptanceService acceptance,
                                 OutboxRepository outbox,
                                 SubmissionRepository submissions,
                                 EngagementSubmissionService submissionService,
                                 EngagementVerificationService verificationService,
                                 EngagementVerificationRepository verifications,
                                 RatingRepository ratings,
                                 MerchantContestCoordinator contests,
                                 com.grassland.marketplace.settlement.SettlementReconciliationRepository reconciliations,
                                 SettlementWorkflowStarter settlementWorkflows,
                                 TransactionalOperator transactions, ReputationService reputationService,
                                 TaskRecommenderInvitationRepository recommenderInvitations) {
        this.callers = callers;
        this.tasks = tasks;
        this.taskAuthorization = taskAuthorization;
        this.apps = apps;
        this.metrics = metrics;
        this.acceptanceCounters = acceptanceCounters;
        this.acceptance = acceptance;
        this.outbox = outbox;
        this.submissions = submissions;
        this.submissionService = submissionService;
        this.verificationService = verificationService;
        this.verifications = verifications;
        this.ratings = ratings;
        this.contests = contests;
        this.reconciliations = reconciliations;
        this.settlementWorkflows = settlementWorkflows;
        this.transactions = transactions;
        this.reputationService = reputationService;
        this.recommenderInvitations = recommenderInvitations;
    }

    @PostMapping(value = "/api/tasks/{id}/applications")
    public Mono<ResponseEntity<Map<String, Object>>> apply(@PathVariable String id,
                                                           @RequestBody(required = false) ApplyRequest body,
                                                           ServerHttpRequest request) {
        String note = body == null ? null : body.note();
        return callers.requireRecommender(request)
                .flatMap(rec -> tasks.findById(id)
                        .switchIfEmpty(fail(404, "任务不存在"))
                        .flatMap(task -> {
                            if (!"published".equals(task.status())) {
                                // #26 D9：closed/cancelled 文案单独拆分（原统一「任务当前不可报名」），其余状态维持原文案
                                String message = "closed".equals(task.status()) ? "任务已关闭，无法报名"
                                        : "cancelled".equals(task.status()) ? "任务已取消，无法报名"
                                        : "任务当前不可报名";
                                return fail(409, message);
                            }
                            // GL-P1-TASK-001 Stage 1：报名截止（PRD「指定时间」）。已截止 → 不接受新报名；
                            // 既有 pending/accepted/履约不受影响（D-03 未决，不动 accept/confirm/结算）。
                            if (task.applicationDeadline() != null
                                    && task.applicationDeadline().isBefore(Instant.now())) {
                                return fail(409, "报名已截止");
                            }
                            return reputationService.snapshot(rec.accountId())
                                    .flatMap(snapshot -> snapshot.evaluation().effectiveLevel().number()
                                                    < task.minRecommenderLevel()
                                            ? Mono.<TaskApplication>error(new MarketplaceException(
                                                    403, "当前等级不满足任务报名要求"))
                                            : slotsFull(task).flatMap(full -> full
                                                    ? Mono.<TaskApplication>error(new MarketplaceException(409, "名额已满"))
                                                    : apps.findByTaskAndRecommender(id, rec.accountId())
                                                            .<TaskApplication>flatMap(existing -> Mono.error(
                                                                    new MarketplaceException(409, "已报名该任务")))
                                                            .switchIfEmpty(transactions.transactional(
                                                                    apps.create(id, rec.accountId(), note,
                                                                            TaskFunds.bountyOrZero(task), TaskFunds.freebieDepositOrZero(task))
                                                                            .switchIfEmpty(Mono.error(new MarketplaceException(409, "已报名该任务")))
                                                                            .flatMap(created -> recommenderInvitations
                                                                                    .markApplied(id, rec.accountId())
                                                                                    .then(outbox.append(ApplicationEvents.envelope(
                                                                                            "ApplicationSubmitted", created,
                                                                                            task.ownerAccountId())))
                                                                                    .thenReturn(created))))));
                        })
                        .map(app -> ResponseEntity.status(HttpStatus.CREATED)
                                .body(Map.of("success", true, "data", ApplicationBodies.toBody(app)))));
    }

    @PostMapping("/api/tasks/{id}/applications/{appId}/accept")
    public Mono<ResponseEntity<Map<String, Object>>> accept(@PathVariable String id, @PathVariable String appId,
                                                            ServerHttpRequest request) {
        String idempotencyKey = acceptanceIdempotencyKey(request);
        return callers.requireUser(request)
                .flatMap(merchant -> loadManageableTask(id, merchant)
                        .flatMap(task -> acceptance.accept(task, appId, merchant, idempotencyKey)))
                .map(ApplicationAcceptanceService.AcceptanceOutcome::response);
    }

    /** 幂等键解析：缺失/空白 → 自动生成；超长（&gt;128）→ 400。 */
    private static String acceptanceIdempotencyKey(ServerHttpRequest request) {
        String value = request.getHeaders().getFirst("Idempotency-Key");
        if (value == null || value.isBlank()) {
            return "auto-" + UUID.randomUUID();
        }
        String normalized = value.trim();
        if (normalized.length() > 128) {
            throw new MarketplaceException(400, "Idempotency-Key 最长 128 字符");
        }
        return normalized;
    }

    @GetMapping("/api/tasks/{id}/applications/{appId}/reservation")
    public Mono<ResponseEntity<Map<String, Object>>> reservation(@PathVariable String id, @PathVariable String appId,
                                                                 ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> loadManageableTask(id, caller)
                        .flatMap(task -> apps.findById(appId)
                                .switchIfEmpty(fail(404, "报名不存在"))
                                .flatMap(app -> {
                                    if (!app.taskId().equals(id)) {
                                        return fail(404, "报名不存在");
                                    }
                                    return acceptance.reservationOutcome(app);
                                })));
    }

    /**
     * 推荐官提交履约交付物（PRD 九第一步）。须是**本人**的、**已接受**的报名；
     * 已有一份待核验的交付物时 409（防重复提交刷屏，被退回后可重交）。
     */
    @PostMapping("/api/tasks/{id}/applications/{appId}/submissions")
    public Mono<ResponseEntity<Map<String, Object>>> submitDeliverable(
            @PathVariable String id, @PathVariable String appId,
            @RequestBody CreateSubmissionRequest body, ServerHttpRequest request) {
        return callers.requireRecommender(request)
                .flatMap(caller -> loadAcceptedApp(id, appId)
                        .filter(app -> caller.accountId().equals(app.recommenderAccountId()))
                        .switchIfEmpty(fail(403, "只能提交自己的履约"))
                        .flatMap(app -> tasks.findById(id)
                                .switchIfEmpty(fail(404, "任务不存在"))
                                // D-03 §5：任务已取消 → 不再接受履约提交（已 accept 未提交者已被退款）。
                                .filter(task -> !"cancelled".equals(task.status()))
                                .switchIfEmpty(fail(409, "任务已取消，不能提交履约"))
                                // 任务书 #23 R3：互动任务必填平台账号标识（核验要比对截图账号）。
                                .flatMap(task -> EngagementSubmissionService.requireInteractionHandle(task, body.platformHandle())
                                // 校验在事务外：逐个 mediaId 经 intelligence 取 metadata，过滤 owner==提交人（IDOR 守卫）。
                                .then(submissionService.validateAttachments(task.organizationId(),
                                                caller.accountId(), appId, body.mediaIds()))
                                        .flatMap(atts -> submissionService.submit(app, appId, caller,
                                                        body.contentUrl(), body.note(), atts, task.ownerAccountId(),
                                                        body.platformHandle())
                                                .flatMap(created -> submissionService.startConfirmation(task, app, created)
                                                        // DB 提交已成功，Temporal 瞬时失败不把提交回成 5xx；dispatcher 扫未标记行补启。
                                                        .onErrorResume(failure -> {
                                                            log.warn("confirmation workflow initial start failed submission={} app={}",
                                                                    created.id(), app.id(), failure);
                                                            return Mono.empty();
                                                        })
                                                        .then(Mono.defer(() -> verificationService.runAndRecord(
                                                                        task, app, created, caller.accountId()))
                                                                .doOnError(failure -> log.warn(
                                                                        "automatic verification failed submission={}",
                                                                        created.id(), failure))
                                                                // Submission is already committed. Verification remains retryable
                                                                // through the explicit checks endpoint and must not turn it into 5xx.
                                                                .onErrorResume(failure -> Mono.empty()))
                                                        .thenReturn(created))
                                                .map(created -> ResponseEntity.status(HttpStatus.CREATED)
                                                        .body(Map.of("success", true,
                                                                "data", ApplicationBodies.submissionWithInputs(created, atts))))))));
    }

    /** 列交付物（含历史）及其附件。商家（任务 owner）与本人推荐官可见。 */
    @GetMapping("/api/tasks/{id}/applications/{appId}/submissions")
    public Mono<ResponseEntity<Map<String, Object>>> listSubmissions(
            @PathVariable String id, @PathVariable String appId, ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> loadSubmissionScoped(id, appId, caller)
                        .flatMap(task -> submissionService.list(appId)
                                .map(rows -> ApplicationBodies.ok(Map.of("submissions", rows)))));
    }

    /**
     * 取某附件的短时下载 URL（草场 Slice 11 Stage 2）。可见性与 listSubmissions 一致（owner 或提交人），
     * 挂接核验与 presigned URL 中转在 {@link EngagementSubmissionService#downloadUrl}（JOIN 限定 application，
     * 防跨履约越权）。media 已删/不可用 → 404。
     */
    @GetMapping("/api/tasks/{id}/applications/{appId}/submissions/{submissionId}/attachments/{mediaId}/download-url")
    public Mono<ResponseEntity<Map<String, Object>>> attachmentDownloadUrl(
            @PathVariable String id, @PathVariable String appId, @PathVariable String submissionId,
            @PathVariable UUID mediaId, ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> loadSubmissionScoped(id, appId, caller)
                        .flatMap(task -> submissionService.downloadUrl(task, appId, submissionId, mediaId)
                                .map(dl -> ApplicationBodies.ok(dl))));
    }

    /** 商家退回补交：submitted → rejected（带原因）。退回后推荐官可修改重交。
     *  <p>D-03 规则 4：补证（退回）次数上限 {@code marketplace.confirmation.supplement-cap}（默认 2）。超出 → 409，
     *  不再允许补证，submission 留 submitted，确认窗口照常跑到自动结算（规则 1）。 */
    @PostMapping("/api/tasks/{id}/applications/{appId}/submissions/{submissionId}/reject")
    public Mono<ResponseEntity<Map<String, Object>>> rejectDeliverable(
            @PathVariable String id, @PathVariable String appId, @PathVariable String submissionId,
            @RequestBody(required = false) ReviewSubmissionRequest body, ServerHttpRequest request) {
        String note = body == null ? null : body.note();
        return callers.requireUser(request)
                .flatMap(merchant -> loadManageableTask(id, merchant)
                        .flatMap(task -> loadAcceptedApp(id, appId)
                                .flatMap(app -> submissionService.reject(task, app, submissionId, note)
                                        .map(rejected -> ApplicationBodies.ok(ApplicationBodies.toBody(rejected))))));
    }

    /**
     * 商家拒绝「系统核实通过」的履约（D-03 §2）→ 先取得本地 durable contest 门闩，再转客服终审。
     * 门闩与 manual/auto confirm 更新同一 application 行；一旦提交，Timer 即使尚未读到 trust 案也不得 capture。
     */
    @PostMapping("/api/tasks/{id}/applications/{appId}/contest")
    public Mono<ResponseEntity<Map<String, Object>>> contest(
            @PathVariable String id, @PathVariable String appId,
            @RequestBody ContestEngagementRequest body, ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(merchant -> loadManageableTask(id, merchant)
                        .flatMap(task -> loadAcceptedApp(id, appId)
                                .flatMap(app -> {
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
                                    return submissions.findPending(appId)
                                            .switchIfEmpty(fail(409, "无待确认的履约凭证"))
                                            .flatMap(submission -> verifications.findBySubmission(submission.id())
                                                    .filter(v -> "passed".equalsIgnoreCase(v.status()))
                                                    .switchIfEmpty(fail(409, "仅系统核实通过的履约可转客服拒绝"))
                                                    // claim 自身单独提交后才允许任何远端动作；与 Timer 的 guarded update 决胜。
                                                    .then(transactions.transactional(
                                                            apps.claimContest(app.id(), task.id(), body.reason())
                                                                    .switchIfEmpty(fail(409, "该履约状态已变"))))
                                                    .then(Mono.defer(() -> apps.findById(app.id())))
                                                    .flatMap(claimed -> contests.dispatch(claimed, task))
                                                    .map(ApplicationBodies::contestedResponse));
                                })));
    }

    /** 商家确认履约 → 启结算窗口。
     *
     * <p><b>必须先有待核验的交付物</b>：此前 confirm 是凭空点的——推荐官交了什么、商家在确认什么，
     * 系统里没有任何记录。现在确认即等于「核验通过这份交付物」，同时把它置为 accepted。
     */
    @PostMapping("/api/tasks/{id}/applications/{appId}/confirm")
    public Mono<ResponseEntity<Map<String, Object>>> confirm(@PathVariable String id, @PathVariable String appId,
                                                             @RequestBody(required = false) ConfirmEngagementRequest body,
                                                             ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(merchant -> loadManageableTask(id, merchant)
                        .flatMap(task -> loadAcceptedApp(id, appId)
                                // contest claim 一旦落地即不允许普通确认；不必等待 trust 案/merchant_rejected_at 完成。
                                .flatMap(app -> app.contestRequestedAt() != null
                                        ? fail(409, "已发起争议，无法确认")
                                        // 已由商家/自动路径确认 → 用确定性 workflow id 补启/收敛后幂等 200。
                                        : app.confirmedAt() != null
                                                ? resumeConfirmedSettlement(task, app)
                                                : requiredDeclaredMetric(app, body)
                                                .flatMap(metric -> transactions.transactional(
                                                        confirmWork(id, appId, task, metric.orElse(null)))
                                                .flatMap(confirmed -> startSettlementWorkflow(task, confirmed))
                                                // 商家确认与 auto-confirm 竞态：事务内 guarded write 失败会抛标记异常并回滚；
                                                // 回读若已确认则幂等 200，否则按原业务错误返回 409。
                                                .onErrorResume(ConfirmationConflict.class, conflict -> apps.findById(appId)
                                                        .filter(current -> "accepted".equals(current.status())
                                                                && current.confirmedAt() != null)
                                                        .flatMap(current -> resumeConfirmedSettlement(task, current))
                                                        .switchIfEmpty(fail(409, conflict.getMessage())))))));
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

    /**
     * 商家给推荐官打分（PRD 五：等级门槛全部依赖评分）。
     *
     * <p><b>必须已确认履约</b>（{@code confirmed_at} 非空）才能评——评分是对「已完成的活」的评价，
     * 允许未完成就打分等于把等级体系的输入变成主观意见。一次履约只评一份（DB UNIQUE → 409），
     * 商家不能反复改分刷高/压低某个推荐官。
     *
     * <p>时序上这是「确认履约」之后紧接着的一步（前端在 confirm 成功后就地展示星级表单），
     * 但它是**独立端点**而非 confirm 的请求体：confirm 返回 202 且触发结算 Saga，
     * 把评分塞进去会让「结算启动了但评分写失败」这种半成功状态无处安放。
     */
    @PostMapping("/api/tasks/{id}/applications/{appId}/rating")
    public Mono<ResponseEntity<Map<String, Object>>> rate(@PathVariable String id, @PathVariable String appId,
                                                          @RequestBody RateEngagementRequest body,
                                                          ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(merchant -> loadManageableTask(id, merchant)
                        .flatMap(task -> loadConfirmedApp(id, appId)
                                .flatMap(app -> ratings.create(app.id(), task.id(), app.recommenderAccountId(),
                                                merchant.accountId(), body.score(), body.comment())
                                        .switchIfEmpty(fail(409, "该履约已评价过"))
                                        .flatMap(rating -> outbox
                                                .append(ApplicationEvents.ratingEnvelope(app, rating))
                                                .thenReturn(rating))
                                        .map(rating -> ResponseEntity.status(HttpStatus.CREATED)
                                                .body(Map.of("success", true, "data", ApplicationBodies.toBody(rating)))))));
    }

    /** 查该履约的评分。商家（任务 owner）与本人推荐官可见；未评价 → {@code data: null}（不是 404）。 */
    @GetMapping("/api/tasks/{id}/applications/{appId}/rating")
    public Mono<ResponseEntity<Map<String, Object>>> ratingOf(@PathVariable String id, @PathVariable String appId,
                                                              ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> apps.findById(appId)
                        .switchIfEmpty(fail(404, "报名不存在"))
                        .filter(app -> app.taskId().equals(id))
                        .switchIfEmpty(fail(404, "报名不存在"))
                        .flatMap(app -> tasks.findById(id)
                                .switchIfEmpty(fail(404, "任务不存在"))
                                .flatMap(task -> {
                                    boolean isRecommender = caller.accountId().equals(app.recommenderAccountId());
                                    Mono<Boolean> canView = isRecommender
                                            ? Mono.just(true)
                                            : taskAuthorization.canManage(task, caller);
                                    return canView.flatMap(allowed -> {
                                        if (!allowed) {
                                            return fail(403, "无权查看该履约的评分");
                                        }
                                        return ratings.findByApplication(appId)
                                                .map(rating -> ResponseEntity.ok(Map.<String, Object>of(
                                                        "success", true, "data", ApplicationBodies.toBody(rating))))
                                                .defaultIfEmpty(ResponseEntity.ok(ApplicationBodies.nullData()));
                                    });
                                })));
    }

    /** 加载报名并校验属该 task + 已确认履约：不存在/越界→404，未确认→409。 */
    private Mono<TaskApplication> loadConfirmedApp(String taskId, String appId) {
        return loadAcceptedApp(taskId, appId)
                .filter(app -> app.confirmedAt() != null)
                .switchIfEmpty(fail(409, "尚未确认履约，暂不能评价"));
    }

    @GetMapping("/api/tasks/{id}/applications/{appId}/settlement")
    public Mono<ResponseEntity<Map<String, Object>>> settlement(@PathVariable String id, @PathVariable String appId,
                                                                ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> loadManageableTask(id, caller)
                        .flatMap(task -> apps.findById(appId)
                                .switchIfEmpty(fail(404, "报名不存在"))
                                .flatMap(app -> {
                                    if (!app.taskId().equals(id)) {
                                        return fail(404, "报名不存在");
                                    }
                                    return settlementOutcome(app);
                                })));
    }

    /** 资金型任务已确认 → 启结算窗口 Saga：202 settling（Timer 后 capture）。双击去重。 */
    private Mono<ResponseEntity<Map<String, Object>>> startSettlementWorkflow(Task task, TaskApplication app) {
        return settlementWorkflows.start(task, app)
        .map(wid -> ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("success", true, "data",
                Map.of("workflowId", wid, "applicationId", app.id(), "status", "settling"))));
    }

    /**
     * 轮询商家确认窗口状态（D-03）：owner 可见。返回 {@code status} + {@code deadline} + {@code remainingSeconds}（估算展示）。
     * <ul>
     *   <li>{@code confirmed_at} 已设 → {@code confirmed}（已确认，结算进行中/已完成，细节走 settlement 轮询）。</li>
     *   <li>未确认 + 有 deadline → {@code awaiting_confirmation} + 倒计时（真正到期由 Temporal Timer 驱动，估算不作判定）。</li>
     *   <li>未确认 + 无 deadline → {@code not_entered}（未提交履约）。</li>
     * </ul>
     */
    @GetMapping("/api/tasks/{id}/applications/{appId}/confirmation")
    public Mono<ResponseEntity<Map<String, Object>>> confirmation(@PathVariable String id,
                                                                  @PathVariable String appId,
                                                                  ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> loadManageableTask(id, caller)
                        .flatMap(task -> apps.findById(appId)
                                .switchIfEmpty(fail(404, "报名不存在"))
                                .filter(app -> app.taskId().equals(id))
                                .switchIfEmpty(fail(404, "报名不存在"))
                                .map(this::confirmationOutcome)));
    }

    private ResponseEntity<Map<String, Object>> confirmationOutcome(TaskApplication app) {
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
    private Mono<ResponseEntity<Map<String, Object>>> settlementOutcome(TaskApplication app) {
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

    /** 加载报名并校验属该 task + accepted：不存在/越界→404，非 accepted→409。 */
    private Mono<TaskApplication> loadAcceptedApp(String taskId, String appId) {
        return apps.findById(appId)
                .switchIfEmpty(fail(404, "报名不存在"))
                .filter(app -> app.taskId().equals(taskId))
                .switchIfEmpty(fail(404, "报名不存在"))
                .filter(app -> "accepted".equals(app.status()))
                .switchIfEmpty(fail(409, "该报名未接受"));
    }

    @PostMapping("/api/tasks/{id}/applications/{appId}/reject")
    public Mono<ResponseEntity<Map<String, Object>>> reject(@PathVariable String id, @PathVariable String appId,
                                                            ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(merchant -> loadManageableTask(id, merchant)
                        .flatMap(task -> loadPendingApp(id, appId)
                                .flatMap(app -> apps.reject(appId, id, merchant.accountId())
                                        .switchIfEmpty(fail(409, "该报名已处理")))
                                .flatMap(app -> outbox
                                        .append(ApplicationEvents.envelope("ApplicationRejected", app, task.ownerAccountId()))
                                        .thenReturn(app)))
                        .map(app -> ResponseEntity.ok(Map.of("success", true, "data", ApplicationBodies.toBody(app)))));
    }

    /**
     * 任务书 #27：批量接受报名（1–50 条）。逐项独立处理，允许部分成功（D2）；批内按传入顺序占名额（D3）。
     * 复用单条 accept 的共享内核 {@link ApplicationAcceptanceService#claimAcceptance}（D6）；单项失败不中断后续项。
     */
    @PostMapping("/api/tasks/{id}/applications/batch-accept")
    public Mono<ResponseEntity<Map<String, Object>>> batchAccept(
            @PathVariable String id, @RequestBody BatchOperationRequest body, ServerHttpRequest request) {
        String baseKey = acceptanceIdempotencyKey(request);
        return callers.requireUser(request)
                .flatMap(merchant -> loadManageableTask(id, merchant)
                        .flatMap(task -> Flux.fromIterable(body.applicationIds())
                                .concatMap(appId -> acceptance.batchItem(
                                        task, merchant, baseKey + ":" + appId, appId))
                                .collectList())
                        .map(results -> ResponseEntity.ok(Map.of("success", true,
                                "data", Map.of("results",
                                        results.stream().map(BatchItemResult::toBody).toList())))));
    }

    /**
     * 任务书 #27：批量拒绝报名（1–50 条）。逐项独立，允许部分成功。
     * 复用单条 reject 的共享逻辑（D6）；仅 pending 可处理。
     */
    @PostMapping("/api/tasks/{id}/applications/batch-reject")
    public Mono<ResponseEntity<Map<String, Object>>> batchReject(
            @PathVariable String id, @RequestBody BatchOperationRequest body, ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(merchant -> loadManageableTask(id, merchant)
                        .flatMap(task -> Flux.fromIterable(body.applicationIds())
                                .concatMap(appId -> apps.findById(appId)
                                        .filter(app -> app.taskId().equals(id))
                                        .filter(app -> ApplicationStatus.PENDING.dbValue().equals(app.status()))
                                        .flatMap(app -> apps.reject(appId, id, merchant.accountId())
                                                .flatMap(rejected -> outbox.append(
                                                                ApplicationEvents.envelope("ApplicationRejected", rejected, task.ownerAccountId()))
                                                        .thenReturn(BatchItemResult.ofOutcome(appId, "rejected")))
                                                .switchIfEmpty(Mono.just(BatchItemResult.failed(appId, "该报名已处理"))))
                                        .switchIfEmpty(Mono.defer(() -> apps.findById(appId)
                                                .filter(app -> app.taskId().equals(id))
                                                .flatMap(app -> Mono.just(
                                                        BatchItemResult.failed(appId, "该报名已处理")))
                                                .switchIfEmpty(Mono.just(
                                                        BatchItemResult.failed(appId, "报名不存在"))))))
                                .collectList())
                        .map(results -> {
                            List<Map<String, Object>> items = results.stream()
                                    .map(BatchItemResult::toBody).toList();
                            return ResponseEntity.ok(Map.of("success", true,
                                    "data", Map.of("results", items)));
                        }));
    }

    @PostMapping("/api/tasks/{id}/applications/{appId}/withdraw")
    public Mono<ResponseEntity<Map<String, Object>>> withdraw(@PathVariable String id, @PathVariable String appId,
                                                              ServerHttpRequest request) {
        return callers.requireRecommender(request)
                .flatMap(rec -> apps.findById(appId)
                        .switchIfEmpty(fail(404, "报名不存在"))
                        .flatMap(app -> {
                            if (!app.taskId().equals(id)) {
                                return fail(404, "报名不存在");
                            }
                            if (!app.recommenderAccountId().equals(rec.accountId())) {
                                return fail(403, "无权操作他人报名");
                            }
                            if (!ApplicationStatus.PENDING.dbValue().equals(app.status())) {
                                return fail(409, "该报名已处理");
                            }
                            return tasks.findById(id)
                                    .switchIfEmpty(fail(404, "任务不存在"))
                                    .flatMap(task -> transactions.transactional(
                                            apps.withdraw(appId, id, rec.accountId())
                                                    .switchIfEmpty(fail(409, "该报名已处理"))
                                                    .flatMap(withdrawn -> outbox.append(ApplicationEvents.envelope("ApplicationWithdrawn", withdrawn, task.ownerAccountId())).thenReturn(withdrawn))));
                        })
                        .map(app -> ResponseEntity.ok(Map.of("success", true, "data", ApplicationBodies.toBody(app)))));
    }

    /**
     * 列报名。**商家（任务 owner）看全部；其他人只看得到自己的那条**。
     *
     * <p>此前是 owner-only（非 owner 一律 403），后果是推荐官在自己的工作台里
     * <b>永远看不到自己报了什么</b>——提交履约、开争议这些本该由推荐官发起的动作也就无从挂载。
     * 改成按调用者过滤：不相干的人拿到空列表，不泄露任何信息，也不必再开一个 /api/me/applications。
     */
    @GetMapping("/api/tasks/{id}/applications")
    public Mono<ResponseEntity<Map<String, Object>>> list(
            @PathVariable String id,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Instant createdAfter,
            @RequestParam(required = false) Instant createdBefore,
            @RequestParam(required = false, defaultValue = "200") int limit,
            ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> tasks.findById(id)
                        .switchIfEmpty(fail(404, "任务不存在"))
                        .flatMap(task -> {
                            return taskAuthorization.canManage(task, caller).flatMap(canManage -> {
                                if (!canManage) {
                                    return apps.findByTaskId(id, status, createdAfter, createdBefore, limit)
                                            .filter(a -> caller.accountId().equals(a.recommenderAccountId()))
                                            .map(ApplicationBodies::toBody).collectList()
                                            .map(visible -> ResponseEntity.ok(Map.of("success", true, "data", visible)));
                                }
                                return apps.findByTaskId(id, status, createdAfter, createdBefore, limit)
                                        .collectList()
                                        .flatMap(applications -> reputationService.snapshots(applications.stream()
                                                        .map(TaskApplication::recommenderAccountId).toList())
                                                .map(snapshots -> applications.stream()
                                                        .map(app -> new RankedApplication(app,
                                                                snapshots.get(app.recommenderAccountId())))
                                                        .sorted((left, right) -> {
                                                            int byWeight = Integer.compare(
                                                                    right.snapshot().evaluation().taskPriorityWeight(),
                                                                    left.snapshot().evaluation().taskPriorityWeight());
                                                            if (byWeight != 0) return byWeight;
                                                            int byCreatedAt = left.application().createdAt()
                                                                    .compareTo(right.application().createdAt());
                                                            if (byCreatedAt != 0) return byCreatedAt;
                                                            return left.application().id()
                                                                    .compareTo(right.application().id());
                                                        })
                                                        .map(ranked -> ApplicationBodies.ranked(
                                                                ranked.application(), ranked.snapshot())).toList()))
                                        .flatMap(visible -> taskProgress(id, task)
                                                .map(stats -> ResponseEntity.ok(Map.of("success", true,
                                                        "data", visible, "stats", stats))));
                            });
                        }));
    }

    /** Aggregated list statistics are separate from the filtered rows so pagination does not distort totals. */
    @GetMapping("/api/tasks/{id}/applications/summary")
    public Mono<ResponseEntity<Map<String, Object>>> applicationSummary(
            @PathVariable String id, ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(caller -> loadManageableTask(id, caller)
                        .flatMap(task -> taskProgress(id, task)
                                .map(stats -> ResponseEntity.ok(Map.of("success", true, "data", stats)))));
    }

    private Mono<Map<String, Object>> taskProgress(String taskId, Task task) {
        return metrics.findProgressByTaskIds(List.of(taskId)).next()
                .defaultIfEmpty(TaskProgress.empty(taskId))
                .map(facts -> {
                    Map<String, Object> stats = new LinkedHashMap<>();
                    stats.put("total", facts.totalApplications());
                    stats.put("pending", facts.pendingApplications());
                    stats.put("reserving", facts.reservingApplications());
                    stats.put("accepted", facts.acceptedApplications());
                    stats.put("rejected", facts.rejectedApplications());
                    stats.put("withdrawn", facts.withdrawnApplications());
                    stats.put("refunded", facts.refundedApplications());
                    stats.put("occupiedSlots", facts.occupiedSlots());
                    stats.put("maxSlots", task.maxSlots());
                    stats.put("remainingSlots", task.maxSlots() == null
                            ? null : Math.max(0, task.maxSlots() - facts.occupiedSlots()));
                    stats.put("submittedDeliverables", facts.submittedDeliverables());
                    stats.put("confirmedDeliverables", facts.confirmedDeliverables());
                    stats.put("settledEngagements", facts.settledEngagements());
                    stats.put("reservedBountyCents", facts.reservedBountyCents());
                    stats.put("settledBountyCents", facts.settledBountyCents());
                    return stats;
                });
    }

    /** 组织级任务仅 owner；门店任务允许当前 MANAGER，且每次操作实时向 Identity 重验。 */
    private Mono<Task> loadManageableTask(String taskId, Caller caller) {
        return taskAuthorization.requireManager(taskId, caller)
                .map(TaskResourceAuthorization.ManagedTask::task);
    }

    /** 加载报名并校验属该 task + pending：不存在/越界→404，非 pending→409。 */
    private Mono<TaskApplication> loadPendingApp(String taskId, String appId) {
        return apps.findById(appId)
                .switchIfEmpty(fail(404, "报名不存在"))
                .filter(app -> app.taskId().equals(taskId))
                .switchIfEmpty(fail(404, "报名不存在"))
                .filter(app -> ApplicationStatus.PENDING.dbValue().equals(app.status()))
                .switchIfEmpty(fail(409, "该报名已处理"));
    }

    /** 名额是否已满：reserving 与 accepted 都通过事务 counter 占位。 */
    private Mono<Boolean> slotsFull(Task task) {
        Integer max = task.maxSlots();
        if (max == null) {
            return Mono.just(false);
        }
        return acceptanceCounters.occupied(task.id()).map(occupied -> occupied >= max);
    }

    /** 加载报名→任务并校验可见性（owner 或提交人）：不存在/越界→404，无权→403。返回 Task（取 orgId）。 */
    private Mono<Task> loadSubmissionScoped(String taskId, String appId, Caller caller) {
        return apps.findById(appId)
                .switchIfEmpty(fail(404, "报名不存在"))
                .filter(app -> app.taskId().equals(taskId))
                .switchIfEmpty(fail(404, "报名不存在"))
                .flatMap(app -> tasks.findById(taskId)
                        .switchIfEmpty(fail(404, "任务不存在"))
                        .flatMap(task -> {
                            if (caller.accountId().equals(app.recommenderAccountId())) {
                                return Mono.just(task);
                            }
                            return taskAuthorization.requireManager(task, caller)
                                    .map(TaskResourceAuthorization.ManagedTask::task)
                                    .onErrorMap(MarketplaceException.class, error ->
                                            error.status() == 403 || error.status() == 404
                                                    ? new MarketplaceException(403, "无权查看该履约的交付物")
                                                    : error);
                        }));
    }

    private record RankedApplication(TaskApplication application, ReputationSnapshot snapshot) {}

    /**
     * 商家触发履约核验（Verification v1）：对交付物跑自动核验（链接可达性；Stage 4 加 AI 视觉）
     * → tri-state 聚合 → upsert 核验记录（7C 事务）→ 返回。商家手动决策仍走 confirm（通过）/reject（退回）。
     */
    @PostMapping("/api/tasks/{id}/applications/{appId}/submissions/{submissionId}/verification/checks")
    public Mono<ResponseEntity<Map<String, Object>>> runVerificationChecks(
            @PathVariable String id, @PathVariable String appId, @PathVariable String submissionId,
            ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(merchant -> loadManageableTask(id, merchant)
                        .flatMap(task -> loadAcceptedApp(id, appId)
                                .flatMap(app -> verificationService.check(
                                                task, app, appId, submissionId, merchant.accountId())
                                        .map(v -> ApplicationBodies.ok(v)))));
    }

    /** Immutable task context for task-mode creation and verification replay. */
    @GetMapping("/api/tasks/{id}/applications/{appId}/task-context")
    public Mono<ResponseEntity<Map<String, Object>>> taskContext(
            @PathVariable String id, @PathVariable String appId, ServerHttpRequest request) {
        return callers.requireUser(request).flatMap(caller -> loadAcceptedApp(id, appId)
                .flatMap(app -> {
                    Mono<Void> authorized = app.recommenderAccountId().equals(caller.accountId())
                            ? Mono.empty()
                            : loadManageableTask(id, caller).then();
                    return authorized.then(verificationService.taskContext(appId))
                            .map(json -> ApplicationBodies.ok(json));
                }));
    }

    @GetMapping("/api/tasks/{id}/applications/{appId}/submissions/{submissionId}/verification/runs")
    public Mono<ResponseEntity<Map<String, Object>>> verificationRuns(
            @PathVariable String id, @PathVariable String appId, @PathVariable String submissionId,
            ServerHttpRequest request) {
        return callers.requireUser(request).flatMap(caller -> loadAcceptedApp(id, appId)
                .flatMap(app -> {
                    Mono<Void> authorized = app.recommenderAccountId().equals(caller.accountId())
                            ? Mono.empty() : loadManageableTask(id, caller).then();
                    return authorized.then(verificationService.runs(appId, submissionId))
                            .map(runs -> ApplicationBodies.ok(Map.of("runs", runs)));
                }));
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
