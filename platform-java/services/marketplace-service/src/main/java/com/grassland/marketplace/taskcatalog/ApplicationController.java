package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.workflow.saga.AcceptanceInput;
import com.grassland.marketplace.workflow.saga.ApplicationReservationWorkflow;
import com.grassland.marketplace.workflow.saga.ApplicationReservationWorkflowImpl;
import com.grassland.marketplace.workflow.saga.SettlementInput;
import com.grassland.marketplace.workflow.saga.SettlementWindowWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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

    private final MarketplaceCallerResolver callers;
    private final TaskRepository tasks;
    private final TaskApplicationRepository apps;
    private final OutboxRepository outbox;
    private final SubmissionRepository submissions;
    private final RatingRepository ratings;
    private final WorkflowClient workflowClient;
    private final com.grassland.marketplace.settlement.SettlementReconciliationRepository reconciliations;
    private final long settlementWindowSeconds;
    private final TransactionalOperator transactions;

    public ApplicationController(MarketplaceCallerResolver callers, TaskRepository tasks,
                                 TaskApplicationRepository apps, OutboxRepository outbox,
                                 SubmissionRepository submissions, RatingRepository ratings,
                                 WorkflowClient workflowClient,
                                 com.grassland.marketplace.settlement.SettlementReconciliationRepository reconciliations,
                                 @Value("${marketplace.settlement.window-seconds:5}") long settlementWindowSeconds,
                                 TransactionalOperator transactions) {
        this.callers = callers;
        this.tasks = tasks;
        this.apps = apps;
        this.outbox = outbox;
        this.submissions = submissions;
        this.ratings = ratings;
        this.workflowClient = workflowClient;
        this.reconciliations = reconciliations;
        this.settlementWindowSeconds = settlementWindowSeconds;
        this.transactions = transactions;
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
                            return slotsFull(task).flatMap(full -> full
                                    ? Mono.<TaskApplication>error(new MarketplaceException(409, "名额已满"))
                                    : apps.findByTaskAndRecommender(id, rec.accountId())
                                            .<TaskApplication>flatMap(existing ->
                                                    Mono.error(new MarketplaceException(409, "已报名该任务")))
                                            .switchIfEmpty(transactions.transactional(
                                                    apps.create(id, rec.accountId(), note)
                                                            .switchIfEmpty(Mono.error(new MarketplaceException(409, "已报名该任务")))
                                                            .flatMap(created -> outbox.append(envelope("ApplicationSubmitted", created, null)).thenReturn(created)))));
                        })
                        .map(app -> ResponseEntity.status(HttpStatus.CREATED)
                                .body(Map.of("success", true, "data", toBody(app)))));
    }

    @PostMapping("/api/tasks/{id}/applications/{appId}/accept")
    public Mono<ResponseEntity<Map<String, Object>>> accept(@PathVariable String id, @PathVariable String appId,
                                                            ServerHttpRequest request) {
        return callers.requireMerchant(request)
                .flatMap(merchant -> loadOwnedTask(id, merchant.accountId())
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
        return transactions.transactional(
                apps.accept(app.id(), app.taskId(), merchant.accountId())
                        .switchIfEmpty(fail(409, "该报名已处理"))
                        .flatMap(a -> outbox.append(envelope("ApplicationAccepted", a, task.ownerAccountId())).thenReturn(a)))
                .map(a -> ResponseEntity.ok(Map.of("success", true, "data", toBody(a))));
    }

    /** 资金型任务 → 启资金预留 Saga：202 Accepted + workflowId。双击去重（WorkflowExecutionAlreadyStarted → 复用 id）。 */
    private Mono<ResponseEntity<Map<String, Object>>> startReservationWorkflow(Task task, TaskApplication app,
                                                                               Caller merchant) {
        AcceptanceInput input = new AcceptanceInput(app.id(), task.id(), merchant.accountId(),
                task.organizationId(), task.bountyCents());
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
                .flatMap(caller -> loadOwnedTask(id, caller.accountId())  // 仅 owner 可轮询预留状态
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
                        .flatMap(app -> submissions.create(appId, caller.accountId(), body.contentUrl(), body.note())
                                .switchIfEmpty(fail(409, "已有待核验的交付物，请等待商家核验或修改后重新提交"))
                                .flatMap(created -> outbox
                                        .append(submissionEnvelope("DeliverableSubmitted", app, created))
                                        .thenReturn(created))
                                .map(created -> ResponseEntity.status(HttpStatus.CREATED)
                                        .body(Map.of("success", true, "data", toBody(created))))));
    }

    /** 列交付物（含历史）。商家（任务 owner）与本人推荐官可见。 */
    @GetMapping("/api/tasks/{id}/applications/{appId}/submissions")
    public Mono<ResponseEntity<Map<String, Object>>> listSubmissions(
            @PathVariable String id, @PathVariable String appId, ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> apps.findById(appId)
                        .switchIfEmpty(fail(404, "报名不存在"))
                        .filter(app -> app.taskId().equals(id))
                        .switchIfEmpty(fail(404, "报名不存在"))
                        .flatMap(app -> tasks.findById(id)
                                .switchIfEmpty(fail(404, "任务不存在"))
                                .flatMap(task -> {
                                    boolean isOwner = caller.accountId().equals(task.ownerAccountId());
                                    boolean isSubmitter = caller.accountId().equals(app.recommenderAccountId());
                                    if (!isOwner && !isSubmitter) {
                                        return fail(403, "无权查看该履约的交付物");
                                    }
                                    return submissions.findByApplication(appId).collectList()
                                            .map(list -> ok(Map.of("submissions",
                                                    list.stream().map(this::toBody).toList())));
                                })));
    }

    /** 商家退回补交：submitted → rejected（带原因）。退回后推荐官可修改重交。 */
    @PostMapping("/api/tasks/{id}/applications/{appId}/submissions/{submissionId}/reject")
    public Mono<ResponseEntity<Map<String, Object>>> rejectDeliverable(
            @PathVariable String id, @PathVariable String appId, @PathVariable String submissionId,
            @RequestBody(required = false) ReviewSubmissionRequest body, ServerHttpRequest request) {
        String note = body == null ? null : body.note();
        return callers.requireMerchant(request)
                .flatMap(merchant -> loadOwnedTask(id, merchant.accountId())
                        .flatMap(task -> loadAcceptedApp(id, appId)
                                .flatMap(app -> submissions.review(submissionId, SubmissionStatus.REJECTED, note)
                                        .switchIfEmpty(fail(409, "该交付物已处理"))
                                        .flatMap(rejected -> outbox
                                                .append(submissionEnvelope("DeliverableRejected", app, rejected))
                                                .thenReturn(rejected))
                                        .map(rejected -> ok(toBody(rejected))))));
    }

    /**
     * 商家确认履约 → 启结算窗口。
     *
     * <p><b>必须先有待核验的交付物</b>：此前 confirm 是凭空点的——推荐官交了什么、商家在确认什么，
     * 系统里没有任何记录。现在确认即等于「核验通过这份交付物」，同时把它置为 accepted。
     */
    @PostMapping("/api/tasks/{id}/applications/{appId}/confirm")
    public Mono<ResponseEntity<Map<String, Object>>> confirm(@PathVariable String id, @PathVariable String appId,
                                                             ServerHttpRequest request) {
        return callers.requireMerchant(request)
                .flatMap(merchant -> loadOwnedTask(id, merchant.accountId())
                        .flatMap(task -> loadAcceptedApp(id, appId)
                                .flatMap(app -> submissions.findPending(appId)
                                        .switchIfEmpty(fail(409, "推荐官尚未提交履约凭证，无法确认"))
                                        .flatMap(pending -> submissions
                                                .review(pending.id(), SubmissionStatus.ACCEPTED, null))
                                        .switchIfEmpty(fail(409, "该交付物已处理"))
                                        .thenReturn(app))
                                .flatMap(app -> apps.confirm(appId, id)
                                        .switchIfEmpty(fail(409, "该报名未接受或已确认"))
                                        .flatMap(confirmed -> outbox
                                                .append(envelope("MerchantConfirmed", confirmed, task.ownerAccountId()))
                                                .thenReturn(confirmed)))
                                .flatMap(app -> startSettlementWorkflow(task, app))));
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
        return callers.requireMerchant(request)
                .flatMap(merchant -> loadOwnedTask(id, merchant.accountId())
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
                                    boolean isOwner = caller.accountId().equals(task.ownerAccountId());
                                    boolean isRecommender = caller.accountId().equals(app.recommenderAccountId());
                                    if (!isOwner && !isRecommender) {
                                        return fail(403, "无权查看该履约的评分");
                                    }
                                    return ratings.findByApplication(appId)
                                            .map(rating -> ResponseEntity.ok(Map.<String, Object>of(
                                                    "success", true, "data", toBody(rating))))
                                            .defaultIfEmpty(ResponseEntity.ok(nullData()));
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
                .flatMap(caller -> loadOwnedTask(id, caller.accountId())  // 仅 owner 可轮询结算状态
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
        long amount = task.bountyCents() == null ? 0L : task.bountyCents();
        SettlementInput input = new SettlementInput(app.id(), task.id(), app.reviewedByAccountId(),
                task.organizationId(), amount, settlementWindowSeconds);
        String workflowId = "settle-" + app.id();
        return Mono.fromCallable(() -> {
            SettlementWindowWorkflow stub = workflowClient.newWorkflowStub(
                    SettlementWindowWorkflow.class,
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
                Map.of("workflowId", wid, "applicationId", app.id(), "status", "settling"))));
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
        return callers.requireMerchant(request)
                .flatMap(merchant -> loadOwnedTask(id, merchant.accountId())
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
                            return transactions.transactional(
                                    apps.withdraw(appId, id, rec.accountId())
                                            .switchIfEmpty(fail(409, "该报名已处理"))
                                            .flatMap(withdrawn -> outbox.append(envelope("ApplicationWithdrawn", withdrawn, null)).thenReturn(withdrawn)));
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
                        .flatMap(task -> apps.findByTaskId(id).collectList()
                                .map(list -> {
                                    boolean isOwner = caller.accountId().equals(task.ownerAccountId());
                                    var visible = isOwner ? list : list.stream()
                                            .filter(a -> caller.accountId().equals(a.recommenderAccountId()))
                                            .toList();
                                    return ResponseEntity.ok(Map.of("success", true,
                                            "data", visible.stream().map(this::toBody).toList()));
                                })));
    }

    /** 加载任务并校验 caller 为 owner（资源级自查，HLD 7.4）：不存在→404，非 owner→403。 */
    private Mono<Task> loadOwnedTask(String taskId, String callerAccountId) {
        return tasks.findById(taskId)
                .switchIfEmpty(fail(404, "任务不存在"))
                .filter(t -> callerAccountId.equals(t.ownerAccountId()))
                .switchIfEmpty(fail(403, "无权操作该任务"));
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

    /** outbox 事件信封。{@code taskOwnerId} 仅 accept/reject 携带（apply/withdraw 为 null）。 */
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

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    /** 交付物事件：带上 application 与交付物两侧的关键字段，供下游（核实引擎/通知）消费。 */
    private EventEnvelope submissionEnvelope(String eventType, TaskApplication app, EngagementSubmission submission) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", app.taskId());
        payload.put("applicationId", app.id());
        payload.put("recommenderAccountId", app.recommenderAccountId());
        payload.put("submissionId", submission.id());
        payload.put("contentUrl", submission.contentUrl());
        payload.put("status", submission.status());
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
        return m;
    }

    private static <T> Mono<T> fail(int status, String message) {
        return Mono.error(new MarketplaceException(status, message));
    }
}
