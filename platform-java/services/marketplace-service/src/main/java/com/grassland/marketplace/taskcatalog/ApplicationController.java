package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * application 聚合 HTTP 入口（草场 Epic 4 / HLD 5.3、10.2）。薄控制器：身份解析（BFF 断言）、
 * 资源级守卫与响应装配；领域逻辑在协作服务——
 *
 * <ul>
 *   <li>{@link ApplicationLifecycleService}：报名/撤销/（批量）拒绝、列表与统计；</li>
 *   <li>{@link ApplicationAcceptanceService}：单条/批量/dispatcher 共享接受内核、预留结局（Slice 4F / ADR-D12）；</li>
 *   <li>{@link EngagementSubmissionService}：交付物提交/退回/附件/下载/确认窗口启动（7C 事务）；</li>
 *   <li>{@link EngagementVerificationService}：自动核验引擎与 runs/task-context 轮询；</li>
 *   <li>{@link EngagementDecisionService}：确认/争议/结算结局/评分（D-02/D-03）。</li>
 * </ul>
 *
 * <p>资源级自查：accept/reject/reservation/settlement 等校验 caller==task.owner（loadManageableTask），
 * withdraw 把 recommender 烧进 WHERE（HLD 7.4）。阻塞的 WorkflowClient 调用在服务内包 {@code boundedElastic}。
 */
@RestController
public class ApplicationController {

    private static final Logger log = LoggerFactory.getLogger(ApplicationController.class);

    private final MarketplaceCallerResolver callers;
    private final TaskRepository tasks;
    private final TaskResourceAuthorization taskAuthorization;
    private final TaskApplicationRepository apps;
    private final ApplicationAcceptanceService acceptance;
    private final ApplicationLifecycleService lifecycle;
    private final EngagementSubmissionService submissionService;
    private final EngagementVerificationService verificationService;
    private final com.grassland.marketplace.workflow.IntelligenceCommentSafetyClient commentSafety;
    private final EngagementDecisionService decisions;

    public ApplicationController(MarketplaceCallerResolver callers, TaskRepository tasks,
                                 TaskResourceAuthorization taskAuthorization,
                                 TaskApplicationRepository apps,
                                 ApplicationAcceptanceService acceptance,
                                 ApplicationLifecycleService lifecycle,
                                 EngagementSubmissionService submissionService,
                                 EngagementVerificationService verificationService,
            com.grassland.marketplace.workflow.IntelligenceCommentSafetyClient commentSafety,
                                 EngagementDecisionService decisions) {
        this.callers = callers;
        this.tasks = tasks;
        this.taskAuthorization = taskAuthorization;
        this.apps = apps;
        this.acceptance = acceptance;
        this.lifecycle = lifecycle;
        this.submissionService = submissionService;
        this.verificationService = verificationService;
        this.commentSafety = commentSafety;
        this.decisions = decisions;
    }

    @PostMapping(value = "/api/tasks/{id}/applications")
    public Mono<ResponseEntity<Map<String, Object>>> apply(@PathVariable String id,
                                                           @RequestBody(required = false) ApplyRequest body,
                                                           ServerHttpRequest request) {
        String note = body == null ? null : body.note();
        return callers.requireRecommender(request)
                .flatMap(rec -> tasks.findById(id)
                        .switchIfEmpty(fail(404, "任务不存在"))
                        .flatMap(task -> lifecycle.apply(task, rec, note)))
                .map(app -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(Map.of("success", true, "data", ApplicationBodies.toBody(app))));
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
                                // 缺口清偿之九：评论任务评论文本契约（必填 ≤500 / 非评论任务拒绝）。
                                .flatMap(task -> EngagementSubmissionService.requireInteractionHandle(task, body.platformHandle())
                                        .then(EngagementSubmissionService.requireCommentText(task, body.commentText()))
                                        .then(commentSafety.guard(task, body.commentText()))
                                // 校验在事务外：逐个 mediaId 经 intelligence 取 metadata，过滤 owner==提交人（IDOR 守卫）。
                                .then(submissionService.validateAttachments(task.organizationId(),
                                                caller.accountId(), appId, body.mediaIds()))
                                        .flatMap(atts -> submissionService.submit(app, appId, caller,
                                                        body.contentUrl(), body.note(), atts, task.ownerAccountId(),
                                                        body.platformHandle(), body.commentText())
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

    /** 商家退回补交：submitted → rejected（带原因）。退回后推荐官可修改重交。补证上限见服务（D-03 规则 4）。 */
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
     * 商家拒绝「系统核实通过」的履约（D-03 §2）→ durable contest 门闩 + 转客服终审（见服务）。
     */
    @PostMapping("/api/tasks/{id}/applications/{appId}/contest")
    public Mono<ResponseEntity<Map<String, Object>>> contest(
            @PathVariable String id, @PathVariable String appId,
            @RequestBody ContestEngagementRequest body, ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(merchant -> loadManageableTask(id, merchant)
                        .flatMap(task -> loadAcceptedApp(id, appId)
                                .flatMap(app -> decisions.contest(task, app, body.reason()))));
    }

    /**
     * 商家确认履约 → 启结算窗口（D-02 阶梯佣金申报指标 / D-03 竞态收敛见服务）。
     */
    @PostMapping("/api/tasks/{id}/applications/{appId}/confirm")
    public Mono<ResponseEntity<Map<String, Object>>> confirm(@PathVariable String id, @PathVariable String appId,
                                                             @RequestBody(required = false) ConfirmEngagementRequest body,
                                                             ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(merchant -> loadManageableTask(id, merchant)
                        .flatMap(task -> loadAcceptedApp(id, appId)
                                .flatMap(app -> decisions.confirm(task, app, body))));
    }

    /**
     * 商家给推荐官打分（PRD 五：等级门槛全部依赖评分）。
     *
     * <p><b>必须已确认履约</b>（{@code confirmed_at} 非空）才能评——评分是对「已完成的活」的评价。
     * 一次履约只评一份（DB UNIQUE → 409），商家不能反复改分刷高/压低某个推荐官。
     * 时序上是「确认履约」后紧接着的独立端点而非 confirm 请求体：confirm 返回 202 且触发结算 Saga，
     * 把评分塞进去会让「结算启动了但评分写失败」这种半成功状态无处安放。
     */
    @PostMapping("/api/tasks/{id}/applications/{appId}/rating")
    public Mono<ResponseEntity<Map<String, Object>>> rate(@PathVariable String id, @PathVariable String appId,
                                                          @RequestBody RateEngagementRequest body,
                                                          ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(merchant -> loadManageableTask(id, merchant)
                        .flatMap(task -> loadConfirmedApp(id, appId)
                                .flatMap(app -> decisions.rate(app, merchant, body)
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
                                        return decisions.rating(appId)
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
                                    return decisions.settlementOutcome(app);
                                })));
    }

    /**
     * 轮询商家确认窗口状态（D-03）：owner 可见。状态机（contest_pending/confirmed/
     * awaiting_confirmation/not_entered）见 {@link EngagementDecisionService#confirmationOutcome}。
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
                                .map(decisions::confirmationOutcome)));
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
                                .flatMap(app -> lifecycle.reject(task, app, merchant))))
                .map(app -> ResponseEntity.ok(Map.of("success", true, "data", ApplicationBodies.toBody(app))));
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
                                .concatMap(appId -> lifecycle.batchRejectItem(task, merchant, appId))
                                .collectList())
                        .map(results -> ResponseEntity.ok(Map.of("success", true,
                                "data", Map.of("results",
                                        results.stream().map(BatchItemResult::toBody).toList())))));
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
                                    .flatMap(task -> lifecycle.withdraw(app, task, rec));
                        })
                        .map(app -> ResponseEntity.ok(Map.of("success", true, "data", ApplicationBodies.toBody(app)))));
    }

    /**
     * 列报名。**商家（任务 owner）看全部**（按声誉权重排序 + 任务统计）；**其他人只看得到自己的那条**——
     * 不相干的人拿到空列表，不泄露任何信息，也不必再开一个 /api/me/applications。
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
                        .flatMap(task -> taskAuthorization.canManage(task, caller).flatMap(canManage -> {
                            if (!canManage) {
                                return lifecycle.ownApplications(id, caller, status, createdAfter, createdBefore, limit)
                                        .map(visible -> ResponseEntity.ok(Map.of("success", true, "data", visible)));
                            }
                            return lifecycle.rankedApplications(id, status, createdAfter, createdBefore, limit)
                                    .flatMap(visible -> lifecycle.taskProgress(id, task)
                                            .map(stats -> ResponseEntity.ok(Map.of("success", true,
                                                    "data", visible, "stats", stats))));
                        })));
    }

    /** Aggregated list statistics are separate from the filtered rows so pagination does not distort totals. */
    @GetMapping("/api/tasks/{id}/applications/summary")
    public Mono<ResponseEntity<Map<String, Object>>> applicationSummary(
            @PathVariable String id, ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(caller -> loadManageableTask(id, caller)
                        .flatMap(task -> lifecycle.taskProgress(id, task)
                                .map(stats -> ResponseEntity.ok(Map.of("success", true, "data", stats)))));
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
     * 商家触发履约核验（Verification v1）：对交付物跑自动核验 → tri-state 聚合 → upsert 核验记录（7C 事务）→ 返回。
     * 商家手动决策仍走 confirm（通过）/reject（退回）。
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

    private static <T> Mono<T> fail(int status, String message) {
        return Mono.error(new MarketplaceException(status, message));
    }
}
