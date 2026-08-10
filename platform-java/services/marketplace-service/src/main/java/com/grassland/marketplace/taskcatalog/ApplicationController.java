package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.reputation.ReputationService;
import com.grassland.marketplace.reputation.ReputationSnapshot;
import com.grassland.marketplace.workflow.IntelligenceMediaClient;
import com.grassland.marketplace.workflow.IntelligenceVerificationClient;
import com.grassland.marketplace.workflow.saga.AcceptanceInput;
import com.grassland.marketplace.workflow.saga.ApplicationReservationWorkflow;
import com.grassland.marketplace.workflow.saga.ApplicationReservationWorkflowImpl;
import com.grassland.marketplace.workflow.saga.ConfirmationWorkflowStarter;
import com.grassland.marketplace.workflow.saga.MerchantContestCoordinator;
import com.grassland.marketplace.workflow.saga.SettlementInput;
import com.grassland.marketplace.workflow.saga.SettlementWorkflowStarter;
import com.grassland.marketplace.workflow.saga.SettlementWindowWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * application 聚合 HTTP 入口（草场 Epic 4 Slice 4B / HLD 5.3、10.2；4F 资金预留 Saga 接线）。
 *
 * <ul>
 *   <li>POST /api/tasks/{id}/applications — 推荐官报名（requireRecommender；任务须 published；名额满 fail-fast 409；
 *       一人一报 409；outbox {@code ApplicationSubmitted}）。</li>
 *   <li>POST /api/tasks/{id}/applications/{appId}/accept — 商家接受（须任务 owner；名额 Java 层校验）。
 *       <b>资金型任务</b>（bounty&gt;0，Slice 4F）：启 {@code ApplicationReservationWorkflow} Saga → <b>202</b> Accepted
 *       + workflowId（异步：pending→reserving→accepted/compensated）；<b>非资金型</b>（bounty null/0）：走 4B 原直连
 *       pending→accepted 同步返回 200。双击去重：{@code WorkflowExecutionAlreadyStarted} → 复用既有 workflowId。</li>
 *   <li>GET /api/tasks/{id}/applications/{appId}/reservation — owner 轮询预留结局（accepted/reserving/compensated+reason）。</li>
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
    private final OutboxRepository outbox;
    private final SubmissionRepository submissions;
    private final SubmissionAttachmentRepository attachments;
    private final IntelligenceMediaClient mediaClient;
    private final IntelligenceVerificationClient verificationClient;
    private final LinkReachabilityChecker linkChecker;
    private final EngagementVerificationRepository verifications;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RatingRepository ratings;
    private final WorkflowClient workflowClient;
    private final ConfirmationWorkflowStarter confirmationWorkflows;
    private final MerchantContestCoordinator contests;
    private final com.grassland.marketplace.settlement.SettlementReconciliationRepository reconciliations;
    private final SettlementWorkflowStarter settlementWorkflows;
    private final ReputationService reputationService;
    private final long confirmationWindowSeconds;
    private final long confirmationReminderLeadSeconds;
    private final int supplementCap;
    private final TransactionalOperator transactions;

    public ApplicationController(MarketplaceCallerResolver callers, TaskRepository tasks,
                                 TaskResourceAuthorization taskAuthorization,
                                 TaskApplicationRepository apps, OutboxRepository outbox,
                                 SubmissionRepository submissions,
                                 SubmissionAttachmentRepository attachments,
                                 IntelligenceMediaClient mediaClient,
                                 IntelligenceVerificationClient verificationClient,
                                 LinkReachabilityChecker linkChecker,
                                 EngagementVerificationRepository verifications,
                                 RatingRepository ratings,
                                 WorkflowClient workflowClient,
                                 ConfirmationWorkflowStarter confirmationWorkflows,
                                 MerchantContestCoordinator contests,
                                 com.grassland.marketplace.settlement.SettlementReconciliationRepository reconciliations,
                                 SettlementWorkflowStarter settlementWorkflows,
                                 @Value("${marketplace.confirmation.window-seconds:5}") long confirmationWindowSeconds,
                                 @Value("${marketplace.confirmation.reminder-lead-seconds:86400}") long confirmationReminderLeadSeconds,
                                 @Value("${marketplace.confirmation.supplement-cap:2}") int supplementCap,
                                 TransactionalOperator transactions, ReputationService reputationService) {
        this.callers = callers;
        this.tasks = tasks;
        this.taskAuthorization = taskAuthorization;
        this.apps = apps;
        this.outbox = outbox;
        this.submissions = submissions;
        this.attachments = attachments;
        this.mediaClient = mediaClient;
        this.verificationClient = verificationClient;
        this.linkChecker = linkChecker;
        this.verifications = verifications;
        this.ratings = ratings;
        this.workflowClient = workflowClient;
        this.confirmationWorkflows = confirmationWorkflows;
        this.contests = contests;
        this.reconciliations = reconciliations;
        this.settlementWorkflows = settlementWorkflows;
        this.confirmationWindowSeconds = confirmationWindowSeconds;
        this.confirmationReminderLeadSeconds = Math.max(0, confirmationReminderLeadSeconds);
        this.supplementCap = Math.max(0, supplementCap);
        this.transactions = transactions;
        this.reputationService = reputationService;
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
                                return fail(409, "任务当前不可报名");
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
                                                                    apps.create(id, rec.accountId(), note, bountyOrZero(task))
                                                                            .switchIfEmpty(Mono.error(new MarketplaceException(409, "已报名该任务")))
                                                                            .flatMap(created -> outbox.append(envelope(
                                                                                    "ApplicationSubmitted", created,
                                                                                    task.ownerAccountId())).thenReturn(created))))));
                        })
                        .map(app -> ResponseEntity.status(HttpStatus.CREATED)
                                .body(Map.of("success", true, "data", toBody(app)))));
    }

    @PostMapping("/api/tasks/{id}/applications/{appId}/accept")
    public Mono<ResponseEntity<Map<String, Object>>> accept(@PathVariable String id, @PathVariable String appId,
                                                            ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(merchant -> loadManageableTask(id, merchant)
                        .flatMap(task -> loadPendingApp(id, appId)
                                .flatMap(app -> slotsFull(task).flatMap(full -> full
                                        ? Mono.<ResponseEntity<Map<String, Object>>>error(
                                                new MarketplaceException(409, "名额已满"))
                                        : isMonetary(task)
                                                ? startReservationWorkflow(task, app, merchant)
                                                : acceptDirectly(task, app, merchant)))));
    }

    /** 资金型任务（Slice 4F）：bounty_cents 非 null 且 &gt;0。 */
    private boolean isMonetary(Task task) {
        return task.bountyCents() != null && task.bountyCents() > 0;
    }

    /** 非资金型任务（bounty null/0）→ 4B 原直连：pending→accepted 同步返回 200。 */
    private Mono<ResponseEntity<Map<String, Object>>> acceptDirectly(Task task, TaskApplication app, Caller merchant) {
        return reputationService.snapshot(app.recommenderAccountId())
                .map(ApplicationController::entitlementSnapshot)
                .flatMap(entitlement -> transactions.transactional(
                        apps.accept(app.id(), app.taskId(), merchant.accountId(), bountyOrZero(task), entitlement)
                                .switchIfEmpty(fail(409, "该报名已处理"))
                                .flatMap(a -> outbox.append(envelope(
                                        "ApplicationAccepted", a, task.ownerAccountId())).thenReturn(a))))
                .map(a -> ResponseEntity.ok(Map.of("success", true, "data", toBody(a))));
    }

    /** task.bountyCents 归一为 long（null → 0）。accept/create 冻结赏金快照用。 */
    private static long bountyOrZero(Task task) {
        return task.bountyCents() == null ? 0L : task.bountyCents();
    }

    /** 资金型任务 → 启资金预留 Saga：202 Accepted + workflowId。双击去重（WorkflowExecutionAlreadyStarted → 复用 id）。 */
    private Mono<ResponseEntity<Map<String, Object>>> startReservationWorkflow(Task task, TaskApplication app,
                                                                               Caller merchant) {
        return reputationService.snapshot(app.recommenderAccountId())
                .map(ApplicationController::entitlementSnapshot)
                .flatMap(entitlement -> apps.snapshotPendingEntitlement(app.id(), task.id(), entitlement)
                        .switchIfEmpty(fail(409, "该报名已处理")))
                .flatMap(frozen -> startReservationWorkflow(task, frozen, merchant.accountId()));
    }

    private Mono<ResponseEntity<Map<String, Object>>> startReservationWorkflow(Task task, TaskApplication app,
                                                                                String merchantAccountId) {
        AcceptanceInput input = new AcceptanceInput(app.id(), task.id(), task.ownerAccountId(),
                task.organizationId(), bountyOrZero(task), merchantAccountId);
        String workflowId = "accept-" + app.id();
        return Mono.fromCallable(() -> {
            ApplicationReservationWorkflow stub = workflowClient.newWorkflowStub(
                    ApplicationReservationWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId(workflowId)
                            .setTaskQueue(ApplicationReservationWorkflowImpl.TASK_QUEUE)
                            .setWorkflowIdReusePolicy(
                                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                            .build());
            WorkflowClient.start(stub::run, input);
            return workflowId;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorResume(WorkflowExecutionAlreadyStarted.class, alreadyStarted -> Mono.just(workflowId))
        .map(wid -> ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("success", true, "data",
                Map.of("workflowId", wid, "applicationId", app.id(), "status", "reserving"))));
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
                                    return reservationOutcome(app);
                                })));
    }

    /** 映射 application DB 状态为预留结局：accepted / reserving；pending（含补偿回退）查最近失败 reason。 */
    private Mono<ResponseEntity<Map<String, Object>>> reservationOutcome(TaskApplication app) {
        String status = app.status();
        if (ApplicationStatus.ACCEPTED.dbValue().equals(status)) {
            return Mono.just(ok(Map.of("status", "accepted")));
        }
        if (ApplicationStatus.RESERVING.dbValue().equals(status)) {
            return Mono.just(ok(Map.of("status", "reserving")));
        }
        // pending（含补偿回退）或其它：查最近 ApplicationReservationFailed 事件的 reason
        return outbox.latestReservationFailureReason(app.id())
                .map(reason -> ok(reasonBody("compensated", reason)))
                .defaultIfEmpty(ok(Map.of("status", status)));  // 无失败记录 → 原状态（pending 等）
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
                                // 校验在事务外：逐个 mediaId 经 intelligence 取 metadata，过滤 owner==提交人（IDOR 守卫）。
                                .flatMap(task -> validateAttachments(task.organizationId(),
                                                caller.accountId(), appId, body.mediaIds())
                                        .flatMap(atts -> workSubmitDeliverable(app, appId, caller,
                                                        body.contentUrl(), body.note(), atts, task.ownerAccountId())
                                                .flatMap(created -> startConfirmationWorkflow(task, app, created)
                                                        // DB 提交已成功，Temporal 瞬时失败不把提交回成 5xx；dispatcher 扫未标记行补启。
                                                        .onErrorResume(failure -> {
                                                            log.warn("confirmation workflow initial start failed submission={} app={}",
                                                                    created.id(), app.id(), failure);
                                                            return Mono.empty();
                                                        })
                                                        .thenReturn(created))
                                                .map(created -> ResponseEntity.status(HttpStatus.CREATED)
                                                        .body(Map.of("success", true,
                                                                "data", submissionBodyWithInputs(created, atts))))))));
    }

    /** 列交付物（含历史）及其附件。商家（任务 owner）与本人推荐官可见。 */
    @GetMapping("/api/tasks/{id}/applications/{appId}/submissions")
    public Mono<ResponseEntity<Map<String, Object>>> listSubmissions(
            @PathVariable String id, @PathVariable String appId, ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> loadSubmissionScoped(id, appId, caller)
                        .flatMap(task -> submissions.findByApplication(appId).collectList()
                                .flatMap(list -> {
                                    if (list.isEmpty()) {
                                        return Mono.just(ok(Map.<String, Object>of("submissions", List.of())));
                                    }
                                    List<String> submissionIds = list.stream().map(EngagementSubmission::id).toList();
                                    Mono<List<EngagementSubmissionAttachment>> attsM =
                                            attachments.findBySubmissionIds(submissionIds).collectList();
                                    Mono<List<EngagementVerification>> verifsM =
                                            verifications.findBySubmissions(submissionIds).collectList();
                                    return Mono.zip(attsM, verifsM)
                                            .map(t -> ok(Map.<String, Object>of("submissions",
                                                    list.stream().map(s -> submissionBodyWithRows(s,
                                                            t.getT1().stream().filter(a -> a.submissionId().equals(s.id())).toList(),
                                                            t.getT2().stream().filter(v -> v.submissionId().equals(s.id()))
                                                                    .findFirst().orElse(null)))
                                                            .toList())));
                                })));
    }

    /**
     * 取某附件的短时下载 URL（草场 Slice 11 Stage 2）。可见性与 listSubmissions 一致（owner 或提交人），
     * 再由 {@link SubmissionAttachmentRepository#findOne} 证 media 确挂该 submission（JOIN 限定 application，防跨履约越权），
     * 最后经 {@link IntelligenceMediaClient} 中转取 intelligence 签发的 presigned URL。media 已删/不可用 → 404。
     */
    @GetMapping("/api/tasks/{id}/applications/{appId}/submissions/{submissionId}/attachments/{mediaId}/download-url")
    public Mono<ResponseEntity<Map<String, Object>>> attachmentDownloadUrl(
            @PathVariable String id, @PathVariable String appId, @PathVariable String submissionId,
            @PathVariable UUID mediaId, ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> loadSubmissionScoped(id, appId, caller)
                        .flatMap(task -> attachments.findOne(appId, submissionId, mediaId.toString())
                                .switchIfEmpty(fail(404, "附件未挂接到该交付物"))
                                .flatMap(att -> mediaClient.downloadUrl(
                                                task.organizationId(), mediaId, "application", appId)
                                        .switchIfEmpty(fail(404, "附件已不可用"))
                                        .map(dl -> ok(downloadBody(dl))))));
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
                                // 先校验 submissionId 存在且属于本 application，再判补证上限：
                                // 顺序反了会对「不存在/不属于本履约」的 id 回 409「补证次数已达上限」，
                                // 把调用方引向「去确认履约或开争议」，而真实原因只是 id 写错（应 404）。
                                .flatMap(app -> submissions.findById(submissionId)
                                        .filter(s -> appId.equals(s.applicationId()))
                                        .switchIfEmpty(fail(404, "交付物不存在"))
                                        .flatMap(target -> submissions.countRejectedByApplication(appId))
                                        .flatMap(rejectedCount -> rejectedCount >= supplementCap
                                                ? Mono.<ResponseEntity<Map<String, Object>>>error(new MarketplaceException(
                                                        409, "补证次数已达上限，请确认履约或发起争议"))
                                                : submissions.review(submissionId, SubmissionStatus.REJECTED, note)
                                                        .switchIfEmpty(fail(409, "该交付物已处理"))
                                                        .flatMap(rejected -> outbox
                                                                .append(submissionEnvelope("DeliverableRejected", app, rejected, List.of(), task.ownerAccountId()))
                                                                .thenReturn(rejected))
                                                        .map(rejected -> ok(toBody(rejected)))))));
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
                                        return contests.dispatch(app, task).map(this::contestedResponse);
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
                                                    .map(this::contestedResponse));
                                })));
    }

    private ResponseEntity<Map<String, Object>> contestedResponse(TaskApplication app) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("applicationId", app.id());
        data.put("status", "contested");
        data.put("reason", app.rejectionReason());
        data.put("disputeId", app.merchantRejectionDisputeId());
        return ok(data);
    }

    /** 商家确认履约 → 启结算窗口。
     *
     * <p><b>必须先有待核验的交付物</b>：此前 confirm 是凭空点的——推荐官交了什么、商家在确认什么，
     * 系统里没有任何记录。现在确认即等于「核验通过这份交付物」，同时把它置为 accepted。
     */
    @PostMapping("/api/tasks/{id}/applications/{appId}/confirm")
    public Mono<ResponseEntity<Map<String, Object>>> confirm(@PathVariable String id, @PathVariable String appId,
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
                                                : transactions.transactional(confirmWork(id, appId, task))
                                                .flatMap(confirmed -> startSettlementWorkflow(task, confirmed))
                                                // 商家确认与 auto-confirm 竞态：事务内 guarded write 失败会抛标记异常并回滚；
                                                // 回读若已确认则幂等 200，否则按原业务错误返回 409。
                                                .onErrorResume(ConfirmationConflict.class, conflict -> apps.findById(appId)
                                                        .filter(current -> "accepted".equals(current.status())
                                                                && current.confirmedAt() != null)
                                                        .flatMap(current -> resumeConfirmedSettlement(task, current))
                                                        .switchIfEmpty(fail(409, conflict.getMessage()))))));
    }

    /** 手动确认领域写：submission accepted + application confirmed + MerchantConfirmed outbox，同一事务。 */
    private Mono<TaskApplication> confirmWork(String taskId, String appId, Task task) {
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
                .flatMap(acceptedSubmission -> apps.confirm(appId, taskId)
                        .switchIfEmpty(Mono.error(new ConfirmationConflict("该报名未接受或已确认"))))
                .flatMap(confirmed -> outbox
                        .append(envelope("MerchantConfirmed", confirmed, task.ownerAccountId()))
                        .thenReturn(confirmed));
    }

    private ResponseEntity<Map<String, Object>> confirmedResponse(TaskApplication app) {
        return ok(Map.of("applicationId", app.id(), "status", "confirmed"));
    }

    private Mono<ResponseEntity<Map<String, Object>>> resumeConfirmedSettlement(
            Task task, TaskApplication application) {
        return settlementWorkflows.start(task, application)
                .thenReturn(confirmedResponse(application));
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
                                                .append(ratingEnvelope(app, rating))
                                                .thenReturn(rating))
                                        .map(rating -> ResponseEntity.status(HttpStatus.CREATED)
                                                .body(Map.of("success", true, "data", toBody(rating)))))));
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
                                                        "success", true, "data", toBody(rating))))
                                                .defaultIfEmpty(ResponseEntity.ok(nullData()));
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
     * 启商家确认窗口 Saga（D-03）：推荐官提交履约即起，3 天（dev 5s）到期未操作 → 自动确认结算。
     *
     * <p>fire-and-forget（同 {@code startSettlementWorkflow}）；submitDeliverable 响应仍是 201（提交回执），
     * 窗口状态经 {@code GET .../confirmation} 轮询。{@code workflowId = "confirm-" + appId}、双击去重
     * （{@code WorkflowExecutionAlreadyStarted} → 复用）。阻塞 WorkflowClient 调用包 {@code boundedElastic}。
     */
    private Mono<String> startConfirmationWorkflow(Task task, TaskApplication app, EngagementSubmission submission) {
        return confirmationWorkflows.start(
                        app.id(), submission.id(), task.organizationId(),
                        confirmationWindowSeconds, confirmationReminderLeadSeconds)
                .flatMap(workflowId -> submissions.markConfirmationWorkflowStarted(submission.id())
                        .thenReturn(workflowId));
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
            return ok(Map.of("applicationId", app.id(), "status", "contest_pending", "reason",
                    app.rejectionReason() == null ? "" : app.rejectionReason()));
        }
        if (app.merchantRejectedAt() != null) {
            return contestedResponse(app);
        }
        if (app.confirmedAt() != null) {
            return ok(Map.of("status", "confirmed"));
        }
        if (app.merchantConfirmDeadlineAt() == null) {
            return ok(Map.of("status", "not_entered"));
        }
        long remainingSeconds = Math.max(0,
                java.time.Duration.between(Instant.now(), app.merchantConfirmDeadlineAt()).toSeconds());
        return ok(Map.of(
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
                    .switchIfEmpty(Mono.just(contestedResponse(app)));
        }
        if (app.confirmedAt() == null) {
            return Mono.just(ok(Map.of("status", "not_confirmed")));
        }
        return reconciliations.findLatestForApplication(app.id())
                .map(this::reconciliationOutcome)
                .switchIfEmpty(outbox.latestSettlementStatus(app.id())
                        .map(this::ok)
                        .defaultIfEmpty(ok(Map.of("status", "settling"))));
    }

    private ResponseEntity<Map<String, Object>> reconciliationOutcome(
            com.grassland.marketplace.settlement.SettlementReconciliation rec) {
        return switch (rec.status()) {
            case "reconciled" -> {
                String reason = rec.finalDecision() == null || rec.finalDecision().isBlank()
                        ? "adjudication" : "adjudication:" + rec.finalDecision();
                yield ok(Map.of("status", "settled", "reason", reason));
            }
            case "blocked" -> ok(Map.of("status", "held",
                    "reason", rec.reason() == null ? "blocked" : rec.reason()));
            default -> ok(Map.of("status", "held", "reason", "reconciliation_pending"));
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
                                        .append(envelope("ApplicationRejected", app, task.ownerAccountId()))
                                        .thenReturn(app)))
                        .map(app -> ResponseEntity.ok(Map.of("success", true, "data", toBody(app)))));
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
                                                    .flatMap(withdrawn -> outbox.append(envelope("ApplicationWithdrawn", withdrawn, task.ownerAccountId())).thenReturn(withdrawn))));
                        })
                        .map(app -> ResponseEntity.ok(Map.of("success", true, "data", toBody(app)))));
    }

    /**
     * 列报名。**商家（任务 owner）看全部；其他人只看得到自己的那条**。
     *
     * <p>此前是 owner-only（非 owner 一律 403），后果是推荐官在自己的工作台里
     * <b>永远看不到自己报了什么</b>——提交履约、开争议这些本该由推荐官发起的动作也就无从挂载。
     * 改成按调用者过滤：不相干的人拿到空列表，不泄露任何信息，也不必再开一个 /api/me/applications。
     */
    @GetMapping("/api/tasks/{id}/applications")
    public Mono<ResponseEntity<Map<String, Object>>> list(@PathVariable String id, ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> tasks.findById(id)
                        .switchIfEmpty(fail(404, "任务不存在"))
                        .flatMap(task -> {
                            return taskAuthorization.canManage(task, caller).flatMap(canManage -> {
                                if (!canManage) {
                                    return apps.findByTaskId(id)
                                            .filter(a -> caller.accountId().equals(a.recommenderAccountId()))
                                            .map(this::toBody).collectList()
                                            .map(visible -> ResponseEntity.ok(Map.of("success", true, "data", visible)));
                                }
                                return apps.findByTaskId(id)
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
                                                        .map(this::toRankedBody).toList()))
                                        .map(visible -> ResponseEntity.ok(Map.of("success", true, "data", visible)));
                            });
                        }));
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

    /** 名额是否已满：max_slots 为空（不限）→ false；否则 accepted 数 >= max。 */
    private Mono<Boolean> slotsFull(Task task) {
        Integer max = task.maxSlots();
        if (max == null) {
            return Mono.just(false);
        }
        return apps.countAcceptedByTask(task.id()).map(c -> c >= max);
    }

    /** outbox 事件信封。{@code taskOwnerId} 携带任务归属（apply/withdraw/accept/reject 全携带），
     *  供 identity 通知中心解析商家侧收件人（Slice 12 Stage 3）；与 {@code recommenderAccountId} 合计覆盖争议双方。 */
    private EventEnvelope envelope(String eventType, TaskApplication app, String taskOwnerId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", app.taskId());
        payload.put("applicationId", app.id());
        payload.put("recommenderAccountId", app.recommenderAccountId());
        payload.put("status", app.status());
        if (taskOwnerId != null) {
            payload.put("taskOwnerId", taskOwnerId);
        }
        return new EventEnvelope(UUID.randomUUID().toString(), eventType, "TaskApplication",
                app.id(), 1, Instant.now(), null, payload);
    }

    private EventEnvelope confirmationEnvelope(
            String eventType, TaskApplication app, String submissionId, String taskOwnerId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", app.taskId());
        payload.put("applicationId", app.id());
        payload.put("submissionId", submissionId);
        payload.put("recommenderAccountId", app.recommenderAccountId());
        payload.put("status", app.status());
        if (taskOwnerId != null) {
            payload.put("taskOwnerId", taskOwnerId);
        }
        String eventId = UUID.nameUUIDFromBytes(
                (eventType + ":" + submissionId).getBytes(StandardCharsets.UTF_8)).toString();
        return new EventEnvelope(eventId, eventType, "TaskApplication",
                app.id(), 1, Instant.now(), null, payload);
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    /** 交付物事件：带上 application 与交付物两侧的关键字段（含附件 mediaIds + taskOwnerId），供下游（核实引擎/通知）消费。 */
    private EventEnvelope submissionEnvelope(String eventType, TaskApplication app, EngagementSubmission submission,
                                             List<AttachmentInput> attachments, String taskOwnerId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", app.taskId());
        payload.put("applicationId", app.id());
        payload.put("recommenderAccountId", app.recommenderAccountId());
        payload.put("submissionId", submission.id());
        payload.put("contentUrl", submission.contentUrl());
        payload.put("status", submission.status());
        if (taskOwnerId != null) {
            payload.put("taskOwnerId", taskOwnerId);
        }
        if (!attachments.isEmpty()) {
            payload.put("mediaIds", attachments.stream().map(a -> a.mediaId().toString()).toList());
        }
        return new EventEnvelope(UUID.randomUUID().toString(), eventType, "EngagementSubmission",
                submission.id(), 1, Instant.now(), null, payload);
    }

    private Map<String, Object> toBody(EngagementSubmission submission) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", submission.id());
        m.put("applicationId", submission.applicationId());
        m.put("recommenderAccountId", submission.recommenderAccountId());
        m.put("contentUrl", submission.contentUrl());
        m.put("note", submission.note());
        m.put("status", submission.status());
        m.put("reviewNote", submission.reviewNote());
        m.put("reviewedAt", submission.reviewedAt() == null ? null : submission.reviewedAt().toString());
        m.put("createdAt", submission.createdAt() == null ? null : submission.createdAt().toString());
        return m;
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

    /**
     * 校验附件（事务外）：逐个 mediaId 经 intelligence 取 metadata。intelligence 已过滤
     * purpose=engagement_attachment && active && 未过期（不符→404→empty）；这里再做 IDOR 守卫——
     * owner 必须是提交人本人，否则 403。media 不可用→404。无附件→空列表。
     */
    private Mono<List<AttachmentInput>> validateAttachments(
            String orgId, String ownerAccountId, String applicationId, List<UUID> mediaIds) {
        if (mediaIds.isEmpty()) {
            return Mono.just(List.of());
        }
        return Flux.fromIterable(mediaIds)
                .concatMap(mediaId -> mediaClient.metadata(
                                orgId, mediaId, "application", applicationId)
                        .switchIfEmpty(fail(404, "附件不存在或已不可用"))
                        .filter(m -> ownerAccountId.equals(m.ownerAccountId()))
                        .switchIfEmpty(fail(403, "不能挂接他人的附件"))
                        .filter(m -> "application".equals(m.domainType())
                                && applicationId.equals(m.domainId()))
                        .switchIfEmpty(fail(404, "附件不属于当前报名"))
                        .map(m -> new AttachmentInput(
                                mediaId, m.mimeType(), m.sizeBytes(), m.domainType(), m.domainId(),
                                m.checksum(), m.status(), 1)))
                .collectList();
    }

    /**
     * 原子提交交付物（7C 事务）：create + attach + outbox 同一 R2DBC 事务，outbox 挂 attach 之后。
     * 任一内层失败（含附件冲突 empty→409）→ 整事务回滚，零残留（无孤儿 submission/附件/事件）。
     */
    private Mono<EngagementSubmission> workSubmitDeliverable(TaskApplication app, String appId, Caller caller,
                                                             String contentUrl, String note,
                                                             List<AttachmentInput> attachmentInputs,
                                                             String taskOwnerId) {
        return transactions.transactional(
                submissions.create(appId, caller.accountId(), contentUrl, note)
                        .switchIfEmpty(fail(409, "已有待核验的交付物，请等待商家核验或修改后重新提交"))
                        .flatMap(created -> attachAll(created.id(), attachmentInputs).thenReturn(created))
                        .flatMap(created -> outbox
                                .append(submissionEnvelope("DeliverableSubmitted", app, created, attachmentInputs, taskOwnerId))
                                .then(apps.setConfirmDeadline(app.id(), app.taskId(), confirmationWindowSeconds))
                                .then(outbox.append(confirmationEnvelope(
                                        "ConfirmationWindowEntered", app, created.id(), taskOwnerId)))
                                .thenReturn(created)));
    }

    private Mono<List<EngagementSubmissionAttachment>> attachAll(String submissionId, List<AttachmentInput> inputs) {
        if (inputs.isEmpty()) {
            return Mono.just(List.of());
        }
        return attachments.attach(submissionId, inputs)
                .switchIfEmpty(fail(409, "附件重复，请勿重复挂接相同附件"));
    }

    /** 交付物响应（提交回执）：带附件列表，附件元数据取自校验阶段的快照。 */
    private Map<String, Object> submissionBodyWithInputs(EngagementSubmission submission, List<AttachmentInput> inputs) {
        Map<String, Object> m = toBody(submission);
        m.put("attachments", inputs.stream().map(this::attachmentInputBody).toList());
        return m;
    }

    /** 交付物响应（列表）：带附件列表 + 核验记录（有则附）。附件元数据取自挂接时快照的 DB 行。 */
    private Map<String, Object> submissionBodyWithRows(EngagementSubmission submission,
                                                       List<EngagementSubmissionAttachment> rows,
                                                       EngagementVerification verification) {
        Map<String, Object> m = toBody(submission);
        m.put("attachments", rows.stream().map(this::attachmentRowBody).toList());
        if (verification != null) {
            m.put("verification", verificationBody(verification));
        }
        return m;
    }

    private Map<String, Object> attachmentInputBody(AttachmentInput a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mediaId", a.mediaId());
        m.put("mimeType", a.mimeType());
        m.put("sizeBytes", a.sizeBytes());
        m.put("domainType", a.domainType());
        m.put("domainId", a.domainId());
        m.put("checksum", a.checksum());
        m.put("mediaStatus", a.status());
        return m;
    }

    private Map<String, Object> attachmentRowBody(EngagementSubmissionAttachment a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mediaId", a.mediaReferenceId());
        m.put("mimeType", a.mimeType());
        m.put("sizeBytes", a.sizeBytes());
        m.put("domainType", a.mediaDomainType());
        m.put("domainId", a.mediaDomainId());
        m.put("checksum", a.mediaChecksum());
        m.put("mediaStatus", a.mediaStatusSnapshot());
        return m;
    }

    private Map<String, Object> downloadBody(IntelligenceMediaClient.MediaDownload dl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("downloadUrl", dl.downloadUrl().toString());
        m.put("expiresAt", dl.expiresAt().toString());
        return m;
    }

    /** 评分事件：带上被评人与分数，供下游（声誉/风控）消费。 */
    private EventEnvelope ratingEnvelope(TaskApplication app, EngagementRating rating) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", app.taskId());
        payload.put("applicationId", app.id());
        payload.put("recommenderAccountId", rating.recommenderAccountId());
        payload.put("ratedByAccountId", rating.ratedByAccountId());
        payload.put("score", rating.score());
        return new EventEnvelope(UUID.randomUUID().toString(), "EngagementRated", "EngagementRating",
                rating.id(), 1, Instant.now(), null, payload);
    }

    private Map<String, Object> toBody(EngagementRating rating) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rating.id());
        m.put("applicationId", rating.applicationId());
        m.put("taskId", rating.taskId());
        m.put("recommenderAccountId", rating.recommenderAccountId());
        m.put("ratedByAccountId", rating.ratedByAccountId());
        m.put("score", rating.score());
        m.put("comment", rating.comment());
        m.put("createdAt", rating.createdAt() == null ? null : rating.createdAt().toString());
        return m;
    }

    /** {@code {success:true, data:null}}——Map.of 不收 null 值，故手写。 */
    private static Map<String, Object> nullData() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("data", null);
        return m;
    }

    private Map<String, Object> reasonBody(String status, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("reason", reason);
        return m;
    }

    private Map<String, Object> toBody(TaskApplication app) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", app.id());
        m.put("taskId", app.taskId());
        m.put("recommenderAccountId", app.recommenderAccountId());
        m.put("status", app.status());
        m.put("note", app.note());
        m.put("reviewedByAccountId", app.reviewedByAccountId());
        m.put("decidedAt", app.decidedAt() == null ? null : app.decidedAt().toString());
        m.put("createdAt", app.createdAt() == null ? null : app.createdAt().toString());
        m.put("reputationLevelAtAccept", app.reputationLevelAtAccept());
        m.put("reputationPolicyVersionAtAccept", app.reputationPolicyVersionAtAccept());
        m.put("settlementDelayDaysAtAccept", app.settlementDelayDaysAtAccept());
        m.put("commissionBonusBpsAtAccept", app.commissionBonusBpsAtAccept());
        m.put("premiumSupportAtAccept", app.premiumSupportAtAccept());
        return m;
    }

    private Map<String, Object> toRankedBody(RankedApplication ranked) {
        Map<String, Object> body = toBody(ranked.application());
        body.put("reputationLevel", ranked.snapshot().evaluation().effectiveLevel().number());
        body.put("reputationTitle", ranked.snapshot().policy()
                .ruleFor(ranked.snapshot().evaluation().effectiveLevel()).title());
        body.put("taskPriorityWeight", ranked.snapshot().evaluation().taskPriorityWeight());
        return body;
    }

    private static ReputationEntitlementSnapshot entitlementSnapshot(ReputationSnapshot snapshot) {
        var evaluation = snapshot.evaluation();
        return new ReputationEntitlementSnapshot(
                evaluation.effectiveLevel().number(), snapshot.policy().version(),
                evaluation.settlementDelayDays(), evaluation.commissionBonusBps(), evaluation.premiumSupport());
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
                                .flatMap(app -> submissions.findByApplication(appId)
                                        .filter(s -> s.id().equals(submissionId))
                                        .next()
                                        .switchIfEmpty(fail(404, "交付物不存在"))
                                        .flatMap(submission -> runAndRecordVerification(task, app, submission)
                                                .map(v -> ok(verificationBody(v)))))));
    }

    /** 跑自动核验并原子落记录（7C 事务：upsert + outbox，outbox 挂 upsert 之后；任一失败零残留）。 */
    private Mono<EngagementVerification> runAndRecordVerification(Task task, TaskApplication app,
                                                                  EngagementSubmission submission) {
        return runVerificationChecks(task, submission)
                .flatMap(outcomes -> transactions.transactional(
                        verifications.upsert(submission.id(), aggregateVerificationStatus(outcomes), checksToJson(outcomes))
                                .flatMap(v -> outbox.append(verificationEnvelope(app, submission, v, outcomes, task.ownerAccountId()))
                                        .thenReturn(v))));
    }

    /**
     * 跑自动核验：链接可达性 + AI 视觉核验（附件截图）。链接结论独立给出；AI 检查失败
     * （intelligence 不可用 / 4xx / 5xx）降级为单项 {@code inconclusive}，不拖垮整次核验。无附件 → 跳过 AI，仅 link。
     */
    private Mono<List<CheckOutcome>> runVerificationChecks(Task task, EngagementSubmission submission) {
        Mono<CheckOutcome> link = linkChecker.check(submission.contentUrl())
                .map(r -> new CheckOutcome("link_reachability", r.status(), r.detail(), Instant.now()));
        return link.flatMap(linkOutcome -> aiVisualCheck(task, submission)
                .map(aiOutcomes -> Stream.concat(Stream.of(linkOutcome), aiOutcomes.stream()).toList()));
    }

    /**
     * AI 视觉核验：取该 submission 已证挂接的附件 mediaIds（已限定本 submission，IDOR 安全）→ intelligence 视觉判断。
     * 无附件 → 空（跳过 AI，仅 link）；intelligence 不可用 / 非 200 → 单项 {@code inconclusive}，不 fail 整次核验。
     */
    private Mono<List<CheckOutcome>> aiVisualCheck(Task task, EngagementSubmission submission) {
        return attachments.findBySubmissionIds(List.of(submission.id())).collectList()
                .flatMap(rows -> {
                    if (rows.isEmpty()) {
                        return Mono.just(List.<CheckOutcome>of());
                    }
                    List<UUID> mediaIds = rows.stream()
                            .map(r -> UUID.fromString(r.mediaReferenceId()))
                            .distinct()
                            .toList();
                    return verificationClient.analyze(task.organizationId(), mediaIds,
                                    task.title(), task.description(), task.platform())
                            .map(a -> List.of(new CheckOutcome("ai_visual", a.status(),
                                    aiVisualDetail(a), Instant.now())))
                            .onErrorResume(e -> Mono.just(List.of(new CheckOutcome("ai_visual",
                                    "inconclusive", "AI 视觉核验暂不可用", Instant.now()))));
                });
    }

    /** 汇总 per-media 明细为单项 ai_visual 的 detail（供商家面板展示）；无明细 → null。 */
    private static String aiVisualDetail(IntelligenceVerificationClient.VerificationAnalysis a) {
        List<IntelligenceVerificationClient.MediaResult> results = a.results();
        if (results.isEmpty()) {
            return null;
        }
        return results.stream()
                .map(r -> r.status() + (r.detail() == null || r.detail().isBlank() ? "" : "：" + r.detail()))
                .collect(Collectors.joining("；"));
    }

    /** 聚合 tri-state：failed > inconclusive > passed；无 check → inconclusive。 */
    private static String aggregateVerificationStatus(List<CheckOutcome> outcomes) {
        if (outcomes.isEmpty()) {
            return "inconclusive";
        }
        if (outcomes.stream().anyMatch(o -> "failed".equalsIgnoreCase(o.status()))) {
            return "failed";
        }
        if (outcomes.stream().anyMatch(o -> "inconclusive".equalsIgnoreCase(o.status()))) {
            return "inconclusive";
        }
        return "passed";
    }

    private List<Map<String, Object>> checksToMaps(List<CheckOutcome> outcomes) {
        return outcomes.stream().map(o -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", o.type());
            m.put("status", o.status());
            m.put("detail", o.detail());
            m.put("checkedAt", o.checkedAt() == null ? null : o.checkedAt().toString());
            return m;
        }).toList();
    }

    private String checksToJson(List<CheckOutcome> outcomes) {
        try {
            return mapper.writeValueAsString(checksToMaps(outcomes));
        } catch (JsonProcessingException e) {
            return "[]";  // 不应发生；兜底空数组
        }
    }

    /** 核验事件：确定性 event_id（type-3 UUID），保 outbox 重试 exactly-once（镜像 SettlementActivityImpl）。 */
    private EventEnvelope verificationEnvelope(TaskApplication app, EngagementSubmission submission,
                                               EngagementVerification v, List<CheckOutcome> outcomes, String taskOwnerId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", app.taskId());
        payload.put("applicationId", app.id());
        payload.put("submissionId", submission.id());
        payload.put("recommenderAccountId", app.recommenderAccountId());
        if (taskOwnerId != null) {
            payload.put("taskOwnerId", taskOwnerId);
        }
        payload.put("status", v.status());
        payload.put("checks", checksToMaps(outcomes));
        String eventId = UUID.nameUUIDFromBytes(
                ("VerificationChecked:" + submission.id()).getBytes(StandardCharsets.UTF_8)).toString();
        return new EventEnvelope(eventId, "VerificationChecked", "EngagementSubmission",
                submission.id(), 1, Instant.now(), null, payload);
    }

    private Map<String, Object> verificationBody(EngagementVerification v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("submissionId", v.submissionId());
        m.put("status", v.status());
        m.put("checks", parseChecks(v.checksJson()));
        m.put("lastCheckedAt", v.lastCheckedAt() == null ? null : v.lastCheckedAt().toString());
        return m;
    }

    /** checksJson 是 jsonb 读出的 JSON 文本；解析回结构化对象，避免响应里二次转义。坏 JSON → 原样字符串。 */
    private Object parseChecks(String json) {
        try {
            return mapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            return json;
        }
    }

    /** 单项核验明细（聚合前的原子结果）。 */
    /** 仅用于手动确认与 auto-confirm 并发：抛出即触发 R2DBC 事务 rollback，外层再回读确认态。 */
    private static final class ConfirmationConflict extends RuntimeException {
        private ConfirmationConflict(String message) {
            super(message);
        }
    }

    private record CheckOutcome(String type, String status, String detail, Instant checkedAt) {}

    private static <T> Mono<T> fail(int status, String message) {
        return Mono.error(new MarketplaceException(status, message));
    }
}
