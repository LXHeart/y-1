package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.analytics.AnalyticsModels.BusinessReport;
import com.grassland.marketplace.analytics.AnalyticsRepository;
import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.security.IdentityStoreAuthorizationClient;
import com.grassland.marketplace.reputation.ReputationService;
import com.grassland.identity.assertion.BackendRole;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * task-catalog HTTP 入口。草场 Epic 4 Slice 4A（HLD 5.3；4B 名额/限额；4F 赏金）+ GL-P1-TASK-001 Stage 1 生命周期。
 *
 * <ul>
 *   <li>POST /api/tasks — 商家发布任务（<b>兼容路径，创建即 published</b>；断言 caller 须 merchant；owner=caller；
 *       organizationId 取请求体；outbox {@code TaskPublished}；同事务落 v1 {@code task_version} 快照）。</li>
 *   <li>POST /api/tasks/draft — 创建草稿（merchant；draft tier 允许；不占发布额度）。</li>
 *   <li>PUT /api/tasks/{id} — 编辑草稿（仅 draft 态；owner；expectedVersion 乐观锁）。</li>
 *   <li>POST /api/tasks/{id}/publish — 发布草稿（owner；tier/额度/资金闸门；落快照；outbox {@code TaskPublished}）。</li>
 *   <li>POST /api/tasks/{id}/close — 关闭报名（published→closed；owner；expectedVersion）。</li>
 *   <li>POST /api/tasks/{id}/cancel — 取消任务（draft|published→cancelled；owner；expectedVersion）。</li>
 *   <li>GET /api/tasks?organizationId=&status= — 列任务（默认 published；任意已登录 caller。非 published status 仅本 org merchant 可查）。</li>
 *   <li>GET /api/tasks/{id} — 任务详情（published 对任意 caller 可见；其余状态仅 owner 可见，否则 404 不泄露）。</li>
 * </ul>
 *
 * <p>身份靠 {@link MarketplaceCallerResolver} 消费 BFF 断言。资源级授权（merchant 确属该 org / owner）服务端自查。
 * close/cancel/deadline 只门控「新报名」(apply)，不动既有 accept/confirm/结算（D-03 未决）。
 */
@RestController
public class TaskController {

    private final MarketplaceCallerResolver callers;
    private final TaskRepository tasks;
    private final TaskReviewRepository taskReviews;
    private final TaskReviewService taskReviewService;
    private final TaskPublishGate publishGate;
    private final OutboxRepository outbox;
    private final TaskApplicationRepository apps;
    private final FinanceEscrowClient finance;
    private final TransactionalOperator transactions;
    private final ReputationService reputationService;
    private final TaskResourceAuthorization taskAuthorization;
    private final TaskMetricsRepository metrics;
    private final AnalyticsRepository analytics;
    private final IdentityStoreAuthorizationClient identityStores;
    private final TaskStoreEnrichment storeEnrichment;

    public TaskController(MarketplaceCallerResolver callers, TaskRepository tasks,
                          TaskReviewRepository taskReviews, OutboxRepository outbox,
                          TaskReviewService taskReviewService, TaskPublishGate publishGate,
                          TaskApplicationRepository apps, FinanceEscrowClient finance,
                          TransactionalOperator transactions, ReputationService reputationService,
                          TaskResourceAuthorization taskAuthorization,
                          TaskMetricsRepository metrics, AnalyticsRepository analytics,
                          IdentityStoreAuthorizationClient identityStores,
                          TaskStoreEnrichment storeEnrichment) {
        this.callers = callers;
        this.tasks = tasks;
        this.taskReviews = taskReviews;
        this.taskReviewService = taskReviewService;
        this.publishGate = publishGate;
        this.outbox = outbox;
        this.apps = apps;
        this.finance = finance;
        this.transactions = transactions;
        this.reputationService = reputationService;
        this.taskAuthorization = taskAuthorization;
        this.metrics = metrics;
        this.analytics = analytics;
        this.identityStores = identityStores;
        this.storeEnrichment = storeEnrichment;
    }

    @PostMapping(value = "/api/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> create(@RequestBody CreateTaskRequest body, ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(caller -> taskAuthorization.requireScope(
                                caller, body.organizationId(), blankToNull(body.storeId()), "manager")
                        .flatMap(access -> transactions.transactional(
                                tasks.acquireOrganizationPublishLock(access.organizationId())
                                        .then(enforcePublishGates(access.organizationId(),
                                                access.permissionTier(), body.bountyCents()))
                                        .then(enforceLadderBudget(body.requirements(), body.bountyCents()))
                                        .then(tasks.create(caller.accountId(), access.organizationId(), body.title(),
                                                body.description(), body.contentForm(), body.platform(), body.maxSlots(),
                                                body.bountyCents(), body.applicationDeadline(), body.minRecommenderLevel(),
                                                access.storeId(), body.requirements(), body.autoAcceptMinLevel()))
                                        .flatMap(taskReviewService::submit))))
                .map(task -> ResponseEntity.status(201).body(Map.of("success", true, "data", toBody(task))));
    }

    /** 创建草稿。draft 创建不占发布额度、不需资金权限（草稿 tier 也可建）。 */
    @PostMapping(value = "/api/tasks/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> createDraft(@RequestBody CreateDraftRequest body, ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(caller -> taskAuthorization.requireScope(
                                caller, body.organizationId(), blankToNull(body.storeId()), "manager")
                        .flatMap(access -> transactions.transactional(
                            tasks.createDraft(caller.accountId(), access.organizationId(), body.title(),
                                    body.description(), body.contentForm(), body.platform(), body.maxSlots(),
                                    body.bountyCents(), body.applicationDeadline(), body.minRecommenderLevel(),
                                    access.storeId(), body.requirements(), body.autoAcceptMinLevel()))))
                .map(task -> ResponseEntity.status(201).body(Map.of("success", true, "data", toBody(task))));
    }

    /** 编辑草稿（仅 draft 态；owner；expectedVersion 乐观锁）。 */
    @PutMapping(value = "/api/tasks/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> update(@PathVariable String id, @RequestBody UpdateTaskRequest body,
                                                            ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(caller -> loadManageableTask(id, caller, "draft")
                        .flatMap(current -> transactions.transactional(
                                enforceLadderBudget(body.requirements() == null
                                                ? current.requirements() : body.requirements(), body.bountyCents())
                                        .then(tasks.updateDraft(id, body.expectedVersion(), body.title(), body.description(),
                                        body.contentForm(), body.platform(), body.maxSlots(), body.bountyCents(),
                                        body.applicationDeadline(), body.minRecommenderLevel(), body.requirements(),
                                        body.autoAcceptMinLevel())
                                        .switchIfEmpty(Mono.error(new MarketplaceException(409, "任务已变更，请刷新后重试")))
                                        .flatMap(task -> outbox.append(taskDraftUpdatedEnvelope(task)).thenReturn(task))))))
                .map(task -> ResponseEntity.ok(Map.of("success", true, "data", toBody(task))));
    }

    /** 提交草稿审核（GL-P2-ADMIN-003 全审：draft→pending_review；闸门仍跑；outbox TaskSubmittedForReview）。 */
    @PostMapping(value = "/api/tasks/{id}/publish", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> publish(@PathVariable String id, @RequestBody TaskLifecycleRequest body,
                                                             ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(caller -> loadManageableTaskAccess(id, caller, "draft")
                        .flatMap(access -> transactions.transactional(
                                tasks.acquireOrganizationPublishLock(access.task().organizationId())
                                .then(enforcePublishGates(access.task().organizationId(),
                                                access.permissionTier(), access.task().bountyCents()))
                                        .then(enforceLadderBudget(access.task().requirements(), access.task().bountyCents()))
                                        .then(tasks.publish(id, body.expectedVersion())
                                                .switchIfEmpty(Mono.error(new MarketplaceException(409, "任务已变更，请刷新后重试")))
                                                .flatMap(taskReviewService::submit)))))
                .map(task -> ResponseEntity.ok(Map.of("success", true, "data", toBody(task))));
    }

    /** 关闭报名（published→closed；owner；expectedVersion）。 */
    @PostMapping("/api/tasks/{id}/close")
    public Mono<ResponseEntity<Map<String, Object>>> close(@PathVariable String id, @RequestBody TaskLifecycleRequest body,
                                                           ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(caller -> loadManageableTask(id, caller, "published")
                        .flatMap(ignored -> transactions.transactional(
                                tasks.close(id, body.expectedVersion())
                                        .switchIfEmpty(Mono.error(new MarketplaceException(409, "任务已变更，请刷新后重试")))
                                        .flatMap(task -> outbox.append(taskClosedEnvelope(task)).thenReturn(task)))))
                .map(task -> ResponseEntity.ok(Map.of("success", true, "data", toBody(task))));
    }

    /**
     * 取消任务（draft|published→cancelled；owner；expectedVersion）。
     *
     * <p>D-03 §5：cancel 视商家违约——已 accept 但<b>未提交凭证</b>的 engagement 全额返还商家（首期无补偿），
     * 并记违约信号（trust 声誉未建，事件先落库）。已提交/核实通过的履约<b>不动</b>，照常结算（其确认窗口继续）。
     * release 不在 task-cancel 事务内（finance HTTP）；release 幂等 + 事件确定性 ⇒ 崩溃安全。退款失败向上抛 5xx；
     * task 已 cancelled 时重复调用会跳过状态迁移并重跑退款，收敛「cancel 已提交、release 尚未完成」间隙。
     */
    @PostMapping("/api/tasks/{id}/cancel")
    public Mono<ResponseEntity<Map<String, Object>>> cancel(@PathVariable String id, @RequestBody TaskLifecycleRequest body,
                                                            ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(caller -> loadManageableTask(id, caller, null)
                        .flatMap(owned -> {
                            String status = owned.status();
                            if (TaskStatus.CANCELLED.dbValue().equals(status)) {
                                return refundAcceptedWithoutSubmission(owned)
                                        .map(count -> ResponseEntity.ok(Map.of("success", true,
                                                "data", cancelBody(owned, count))));
                            }
                            if (!TaskStatus.DRAFT.dbValue().equals(status)
                                    && !TaskStatus.PUBLISHED.dbValue().equals(status)
                                    && !TaskStatus.PENDING_REVIEW.dbValue().equals(status)) {
                                return Mono.<ResponseEntity<Map<String, Object>>>error(
                                        new MarketplaceException(409, "任务已结束，不可取消"));
                            }
                            return transactions.transactional(
                                    tasks.cancel(id, body.expectedVersion())
                                            .switchIfEmpty(Mono.error(new MarketplaceException(409, "任务已变更，请刷新后重试")))
                                            .flatMap(task -> outbox.append(taskCancelledEnvelope(task)).thenReturn(task)))
                                    .flatMap(task -> refundAcceptedWithoutSubmission(task)
                                            .map(refundedCount -> ResponseEntity.ok(Map.of("success", true,
                                                    "data", cancelBody(task, refundedCount)))));
                        }));
    }

    /**
     * 退还本任务「已 accept 未提交凭证」的 engagement（D-03 §5）：逐条 finance release（全额返商家）+
     * outbox {@code EngagementRefundedOnCancel}（违约信号 + 双方通知）。失败向上抛；cancel 重试会再次执行（两侧幂等）。
     *
     * <p>release 幂等（404/409 视作成功）后必须把 application 置终态 {@code refunded}：留在 accepted 会让推荐官
     * 侧一直显示「进行中且可提交」（提交已被 cancelled 校验拒），且每次 cancel 重试都重复 release + 重复通知。
     * 状态流转与 outbox append 同事务，保证「已退款 ⇔ 已通知」。
     */
    private Mono<Integer> refundAcceptedWithoutSubmission(Task task) {
        return apps.findAcceptedByTaskWithoutSubmission(task.id())
                .concatMap(app -> finance.release(task.organizationId(), app.id())
                        .then(transactions.transactional(
                                apps.markRefunded(app.id(), task.id())
                                        .flatMap(refunded -> outbox.append(engagementRefundedEnvelope(task, refunded))
                                                .thenReturn(1))))
                        .defaultIfEmpty(0))
                .reduce(0, Integer::sum);
    }

    /**
     * 修订已发布任务（GL-P1-TASK-001：编辑出新版本，全字段）。
     *
     * <p>owner + published；乐观锁；赏金变更走 tier 闸门（{@link #enforceBountyTierGate}，与发布同口径但不占额度）。
     * 全字段可改——accept/结算读 task_application.bounty_cents 快照（V14），已 accept 履约不受影响。每次修订 version+1
     * + 新 task_version 快照 + outbox TaskRevised。
     */
    @PostMapping(value = "/api/tasks/{id}/revise", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> revise(@PathVariable String id, @RequestBody ReviseTaskRequest body,
                                                            ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(caller -> loadManageableTaskAccess(id, caller, "published")
                        .flatMap(access -> enforceBountyTierGate(access.permissionTier(), body.bountyCents())
                                .then(enforceLadderBudget(body.requirements() == null
                                                ? access.task().requirements() : body.requirements(), body.bountyCents()))
                                .thenReturn(access.task())
                                .flatMap(v -> transactions.transactional(
                                        tasks.revisePublished(id, body.expectedVersion(), body.title(), body.description(),
                                                body.contentForm(), body.platform(), body.maxSlots(), body.bountyCents(),
                                                body.applicationDeadline(), body.minRecommenderLevel(),
                                                body.requirements(), caller.accountId(), body.autoAcceptMinLevel())
                                                .switchIfEmpty(Mono.error(new MarketplaceException(409, "任务已变更，请刷新后重试")))
                                                .flatMap(task -> outbox.append(taskRevisedEnvelope(task)).thenReturn(task))))))
                .map(task -> ResponseEntity.ok(Map.of("success", true, "data", toBody(task))));
    }

    /**
     * 修订赏金的 tier 闸门：资金型（bounty&gt;0）须有交易权限；赏金 ≤ 本组织单笔上限。与发布同口径，
     * 但<b>不算 active/monthly 额度</b>——修订不是新发布，任务已在额度内。防止商家借修订把赏金抬到 tier 之上。
     */
    private Mono<Void> enforceBountyTierGate(String permissionTier, Long bountyCents) {
        MerchantTier tier = MerchantTier.fromDb(permissionTier);
        long bounty = bountyCents == null ? 0L : bountyCents;
        long maxTx = PublishQuotaPolicy.maxTxAmountCents(tier);
        if (bounty > 0 && maxTx == 0) {
            return Mono.error(new MarketplaceException(403, "当前等级不可发布资金型任务"));
        }
        if (bounty > maxTx) {
            return Mono.error(new MarketplaceException(409, "赏金超出本组织单笔上限"));
        }
        return Mono.empty();
    }

    private Mono<Void> enforceLadderBudget(TaskRequirements requirements, Long bountyCents) {
        if (requirements != null && requirements.commissionLadder() != null) {
            try {
                requirements.commissionLadder().validateReserve(bountyCents);
            } catch (IllegalArgumentException error) {
                return Mono.error(new MarketplaceException(400, error.getMessage()));
            }
        }
        return Mono.empty();
    }

    @GetMapping("/api/tasks")
    public Mono<ResponseEntity<Map<String, Object>>> list(@RequestParam String organizationId,
                                                          @RequestParam(required = false, defaultValue = "published") String status,
                                                          @RequestParam(required = false) String storeId,
                                                          ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> {
                    if (storeId != null && !storeId.isBlank()) {
                        return taskAuthorization.requireScope(caller, organizationId, storeId, "staff")
                                .then(tasks.findByStore(organizationId, storeId, status).collectList())
                                .flatMap(this::enrichTasks)
                                .map(list -> ResponseEntity.ok(Map.of("success", true, "data", list)));
                    }
                    // 非 published status 仅本 org merchant 可查（防跨组织草稿/取消泄露）。
                    String effectiveStatus = TaskStatus.PUBLISHED.dbValue().equalsIgnoreCase(status) || status.isBlank()
                            ? TaskStatus.PUBLISHED.dbValue()
                            : (caller.isMerchant() && organizationId.equals(caller.organizationId())
                                    ? status
                                    : TaskStatus.PUBLISHED.dbValue());
                    boolean ownerView = caller.isMerchant() && organizationId.equals(caller.organizationId());
                    Mono<List<Task>> visibleTasks = ownerView
                            ? tasks.findByOrganization(organizationId, effectiveStatus).collectList()
                            : visibleRecommenderLevel(caller).flatMap(level ->
                                    tasks.findByOrganization(organizationId, effectiveStatus)
                                            .filter(task -> !TaskStatus.PUBLISHED.dbValue().equals(task.status())
                                                    || task.minRecommenderLevel() <= level)
                                            .collectList());
                    return visibleTasks.flatMap(this::enrichTasks)
                            .map(list -> ResponseEntity.ok(Map.of("success", true, "data", list)));
                });
    }

    /** Merchant analytics read model. Marketing fields stay truthful when only Sandbox events are present. */
    @GetMapping("/api/tasks/analytics")
    public Mono<ResponseEntity<Map<String, Object>>> analytics(
            @RequestParam String organizationId,
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(caller -> taskAuthorization.requireScope(
                                caller, organizationId, blankToNull(storeId), "staff")
                                .flatMap(access -> Mono.zip(
                                        metrics.dashboard(access.organizationId(), access.storeId(), from, to),
                                        analytics.report(access.organizationId(), access.storeId(), from, to))))
                .map(tuple -> ResponseEntity.ok(Map.of("success", true,
                        "data", dashboardBody(tuple.getT1(), tuple.getT2()))));
    }

    // ---------- 任务内容审核（GL-P2-ADMIN-003 全审政策）----------

    /** 待审核任务队列（内容审核员视角）。门闩 requireRole(CONTENT_REVIEWER)，PLATFORM_ADMIN 超集。 */
    @GetMapping("/api/admin/tasks/review")
    public Mono<ResponseEntity<Map<String, Object>>> listPendingReview(
            @org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "50") int limit,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String status,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String organizationId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String platform,
            @org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "false") boolean overdue,
            @org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "0") int offset,
            ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.CONTENT_REVIEWER)
                .thenMany(tasks.findReviewQueue(blankToNull(status), blankToNull(organizationId),
                                blankToNull(platform), overdue, limit, offset).map(this::toBody))
                .collectList()
                .flatMap(items -> taskReviews.queueStats()
                        .map(stats -> ResponseEntity.ok(Map.of("success", true, "data", items,
                                "meta", Map.of("offset", Math.max(0, offset), "limit", Math.max(1, Math.min(limit, 200)),
                                        "queue", Map.of("pending", stats.pending(), "overdue", stats.overdue(),
                                                "approvedLast24Hours", stats.approvedLast24Hours(),
                                                "rejectedLast24Hours", stats.rejectedLast24Hours()))))));
    }

    /** Append-only review history for audit drill-down. */
    @GetMapping("/api/admin/tasks/{id}/review/history")
    public Mono<ResponseEntity<Map<String, Object>>> reviewHistory(
            @PathVariable String id,
            @org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "100") int limit,
            @org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "0") int offset,
            ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.CONTENT_REVIEWER)
                .then(tasks.findById(id).switchIfEmpty(Mono.error(new MarketplaceException(404, "任务不存在"))))
                .then(taskReviews.findHistory(id, limit, offset).map(TaskController::reviewBody).collectList())
                .map(items -> ResponseEntity.ok(Map.of("success", true, "data", items,
                        "meta", Map.of("offset", Math.max(0, offset),
                                "limit", Math.max(1, Math.min(limit, 200))))));
    }

    @GetMapping("/api/admin/tasks/review/stats")
    public Mono<ResponseEntity<Map<String, Object>>> reviewStats(ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.CONTENT_REVIEWER)
                .then(taskReviews.queueStats())
                .map(stats -> ResponseEntity.ok(Map.of("success", true, "data", Map.of(
                        "pending", stats.pending(), "overdue", stats.overdue(),
                        "approvedLast24Hours", stats.approvedLast24Hours(),
                        "rejectedLast24Hours", stats.rejectedLast24Hours()))));
    }

    /**
     * 审核通过（pending_review→published，正式上架）。
     *
     * <p>闸门 4+5（活跃/月度额度）重跑——审核期间商家可能别处又发了任务导致额度已满。
     * 闸门 2+3（tier/资金）也重跑——审核期间 tier 可能被降。
     * owner tier 从 task 行的 ownerAccountId + organizationId 反查（审核员不是 merchant）。
     */
    @PostMapping(value = "/api/admin/tasks/{id}/review/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> reviewApprove(
            @PathVariable String id, @RequestBody TaskLifecycleRequest body, ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.CONTENT_REVIEWER)
                .flatMap(reviewer -> tasks.findById(id)
                        .switchIfEmpty(Mono.error(new MarketplaceException(404, "任务不存在")))
                        .flatMap(task -> {
                            if (!TaskStatus.PENDING_REVIEW.dbValue().equals(task.status())) {
                                return Mono.<Task>error(new MarketplaceException(409, "该任务不在待审核状态"));
                            }
                            return taskAuthorization.requireCurrentOwnerManager(task)
                                    .flatMap(access -> transactions.transactional(
                                            tasks.acquireOrganizationPublishLock(task.organizationId())
                                                    .then(enforcePublishGates(task.organizationId(),
                                                            access.permissionTier(), task.bountyCents()))
                                                    .then(enforceLadderBudget(task.requirements(), task.bountyCents()))
                                                    .then(tasks.reviewApprove(id, body.expectedVersion(), reviewer.accountId())
                                                            .switchIfEmpty(Mono.error(new MarketplaceException(
                                                                    409, "任务已变更，请刷新后重试")))
                                                            .flatMap(approved -> taskReviews.append(
                                                                            id, "approved", reviewer.accountId(), null)
                                                                    .then(outbox.append(taskPublishedEnvelope(approved)))
                                                                    .thenReturn(approved)))));
                        }))
                .map(task -> ResponseEntity.ok(Map.of("success", true, "data", toBody(task))));
    }

    /**
     * 审核驳回（pending_review→draft，退回让商家修改后重新提交）。
     */
    @PostMapping(value = "/api/admin/tasks/{id}/review/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> reviewReject(
            @PathVariable String id, @RequestBody TaskReviewRequest body, ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.CONTENT_REVIEWER)
                .flatMap(reviewer -> {
                    String note = body.requireNote();
                    return tasks.findById(id)
                            .switchIfEmpty(Mono.error(new MarketplaceException(404, "任务不存在")))
                            .flatMap(task -> {
                                if (!TaskStatus.PENDING_REVIEW.dbValue().equals(task.status())) {
                                    return Mono.<Task>error(new MarketplaceException(409, "该任务不在待审核状态"));
                                }
                                return transactions.transactional(
                                        tasks.reviewReject(id, body.expectedVersion())
                                                .switchIfEmpty(Mono.error(new MarketplaceException(409, "任务已变更，请刷新后重试")))
                                                .flatMap(rejected -> taskReviews.append(id, "rejected", reviewer.accountId(), note)
                                                        .then(outbox.append(taskRejectedEnvelope(rejected, note)))
                                                        .thenReturn(rejected)));
                            });
                })
                .map(task -> ResponseEntity.ok(Map.of("success", true, "data", toBody(task))));
    }

    /**
     * 全局任务大厅（GL-P1-TASK-001 Stage 2）：跨组织 feed，仅 published 且未截止。
     *
     * <p>任意已登录 caller 可查（推荐官浏览大厅）。keyset 游标分页（{@code created_at DESC, id DESC}），
     * 筛选 platform/contentForm/minBountyCents；距离筛选通过 Identity 的权威门店坐标先解析门店集合，
     * 再进入本服务 keyset 查询，避免客户端逐页过滤造成漏项。
     * 响应 {@code {items, nextCursor, hasMore}}，与按 org 的 {@code GET /api/tasks} 裸数组形状区分。
     * 路由字面量 {@code feed} 在 PathPattern 优先于 {@code {id}}，命中既有 {@code /api/tasks**} BFF flag。
     */
    @GetMapping("/api/tasks/feed")
    public Mono<ResponseEntity<Map<String, Object>>> feed(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String contentForm,
            @RequestParam(required = false) Long minBountyCents,
            @RequestParam(required = false) Double latitude,
            @RequestParam(required = false) Double longitude,
            @RequestParam(required = false) Double maxDistanceKm,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "20") int limit,
            ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> {
                    int safeLimit = Math.max(1, Math.min(limit, 50));
                    FeedCursor decoded = FeedCursor.decode(cursor);
                    boolean anyDistance = latitude != null || longitude != null || maxDistanceKm != null;
                    if (anyDistance && !validDistanceQuery(latitude, longitude, maxDistanceKm)) {
                        return Mono.error(new MarketplaceException(400,
                                "距离筛选需提供有效经纬度，范围须在 0.1 至 200 公里之间"));
                    }
                    Mono<List<IdentityStoreAuthorizationClient.NearbyStore>> nearby = anyDistance
                            ? identityStores.nearby(latitude, longitude, maxDistanceKm)
                            : Mono.just(List.of());
                    return Mono.zip(visibleRecommenderLevel(caller), nearby).flatMap(tuple -> {
                        List<IdentityStoreAuthorizationClient.NearbyStore> nearbyStores = tuple.getT2();
                        if (anyDistance && nearbyStores.isEmpty()) {
                            return Mono.just(feedBody(List.of(), safeLimit, Map.of(), Map.of()));
                        }
                        List<String> storeIds = anyDistance
                                ? nearbyStores.stream().map(IdentityStoreAuthorizationClient.NearbyStore::storeId).toList()
                                : null;
                        Map<String, Double> distances = nearbyStores.stream().collect(Collectors.toMap(
                                IdentityStoreAuthorizationClient.NearbyStore::storeId,
                                IdentityStoreAuthorizationClient.NearbyStore::distanceKm,
                                Math::min));
                        TaskRepository.FeedFilter filter = new TaskRepository.FeedFilter(
                                blankToNull(platform), blankToNull(contentForm),
                                (minBountyCents == null || minBountyCents < 0) ? null : minBountyCents,
                                tuple.getT1(), storeIds);
                        return tasks.findFeed(filter,
                                        decoded == null ? null : decoded.ts(),
                                        decoded == null ? null : decoded.id(), safeLimit + 1)
                                .collectList()
                                .flatMap(rows -> enrichFeed(rows, safeLimit, distances));
                    });
                });
    }

    /**
     * 任务书 #24：feed 门店块增强。keyset 分页每页最多 limit+1 行，只对页内去重后的 storeId
     * 一次批量拉 identity（不逐行）；distanceKm 逻辑不动。
     */
    private Mono<ResponseEntity<Map<String, Object>>> enrichFeed(
            List<Task> rows, int limit, Map<String, Double> distances) {
        boolean hasMore = rows.size() > limit;
        List<Task> page = hasMore ? rows.subList(0, limit) : rows;
        List<String> pageStoreIds = page.stream().map(Task::storeId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        return storeEnrichment.loadStoreBlocks(pageStoreIds)
                .map(stores -> feedBody(rows, limit, distances, stores));
    }

    /** 组装 feed 分页体：取 limit+1 判 hasMore，nextCursor 为本页最后一行的 (created_at, id)。 */
    private ResponseEntity<Map<String, Object>> feedBody(
            List<Task> rows, int limit, Map<String, Double> distances,
            Map<String, Map<String, Object>> stores) {
        boolean hasMore = rows.size() > limit;
        List<Task> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore && !page.isEmpty() ? FeedCursor.encode(page.get(page.size() - 1)) : null;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", page.stream().map(task -> {
            Map<String, Object> body = toBody(task);
            if (task.storeId() != null && distances.containsKey(task.storeId())) {
                body.put("distanceKm", Math.round(distances.get(task.storeId()) * 10d) / 10d);
            }
            if (task.storeId() != null && stores.containsKey(task.storeId())) {
                body.put("store", stores.get(task.storeId()));
            }
            return body;
        }).toList());
        data.put("nextCursor", nextCursor);
        data.put("hasMore", hasMore);
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    private static boolean validDistanceQuery(Double latitude, Double longitude, Double radiusKm) {
        return latitude != null && longitude != null && radiusKm != null
                && Double.isFinite(latitude) && latitude >= -90 && latitude <= 90
                && Double.isFinite(longitude) && longitude >= -180 && longitude <= 180
                && Double.isFinite(radiusKm) && radiusKm >= 0.1 && radiusKm <= 200;
    }

    /**
     * 本组织的发布用量（D-05 额度的「已用」侧）。
     *
     * <p>补 identity {@code GET /api/organizations/{orgId}/quota} 的缺口——那里只给**上限**
     * （策略归 identity 的 {@code PermissionQuotaPolicy} 所有），用量在 marketplace 这侧。
     * 前端把两者合并展示为「已用 N / 上限 M」。
     *
     * <p>刻意<b>只回用量、不回上限</b>：上限已在 identity 与本服务的 {@link PublishQuotaPolicy}
     * 两处镜像（靠单测锁值防漂移），再加第三处只会多一个漂移点。
     *
     * <p>路由放在 {@code /api/tasks/*} 下，命中 edge-bff 既有的 {@code /api/tasks**} 前缀，无需新增 BFF 路由。
     * 字面量段 {@code usage} 在 PathPattern 里优先级高于 {@code {id}} 模板，不会被详情端点抢走。
     */
    @GetMapping("/api/tasks/usage")
    public Mono<ResponseEntity<Map<String, Object>>> usage(@RequestParam String organizationId,
                                                           ServerHttpRequest request) {
        return callers.requireMerchant(request)
                .flatMap(merchant -> {
                    // org 归属自查，与发布闸门 1 同口径：不能查别家组织的用量。
                    if (!organizationId.equals(merchant.organizationId())) {
                        return Mono.<ResponseEntity<Map<String, Object>>>error(
                                new MarketplaceException(403, "无权查询该组织用量"));
                    }
                    MerchantTier tier = MerchantTier.fromDb(merchant.permissionTier());
                    int maxActive = PublishQuotaPolicy.maxActiveTasks(tier);
                    int maxMonthly = PublishQuotaPolicy.maxMonthlyTasks(tier);
                    long maxTx = PublishQuotaPolicy.maxTxAmountCents(tier);
                    return tasks.countActiveByOrganization(organizationId)
                            .flatMap(active -> tasks.countCreatedThisMonthByOrganization(organizationId)
                                    .map(monthly -> ResponseEntity.ok(Map.of("success", true, "data", Map.of(
                                            "organizationId", organizationId,
                                            "activeTasks", active,
                                            "monthlyTasks", monthly,
                                            "maxActiveTasks", maxActive,
                                            "remainingActiveTasks", Math.max(0, maxActive - active),
                                            "maxMonthlyTasks", maxMonthly,
                                            "remainingMonthlyTasks", Math.max(0, maxMonthly - monthly),
                                            "maxTxAmountCents", maxTx)))));
                });
    }

    @GetMapping("/api/tasks/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> get(@PathVariable String id, ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> tasks.findById(id)
                        .switchIfEmpty(Mono.error(new MarketplaceException(404, "任务不存在")))
                        .flatMap(task -> {
                            // published 对任意 caller 可见；其余状态仅 owner 可见（不泄露 draft/closed/cancelled 存在）。
                            boolean publicVisible = TaskStatus.PUBLISHED.dbValue().equals(task.status());
                            boolean owner = caller.accountId().equals(task.ownerAccountId());
                            if (!publicVisible && task.storeId() != null) {
                                return taskAuthorization.requireScope(caller, task.organizationId(),
                                                task.storeId(), "staff")
                                        .then(okWithStore(task));
                            }
                            if (owner && task.storeId() == null) {
                                return okWithStore(task);
                            }
                            if (!publicVisible) {
                                return Mono.error(new MarketplaceException(404, "任务不存在"));
                            }
                            return visibleRecommenderLevel(caller)
                                    .filter(level -> level >= task.minRecommenderLevel())
                                    .flatMap(level -> okWithStore(task))
                                    .switchIfEmpty(Mono.error(new MarketplaceException(404, "任务不存在")));
                        }));
    }

    /** 任务书 #24：任务详情携带门店公开块（storeName/city/categories）；无门店/降级时不带 store 键。 */
    private Mono<ResponseEntity<Map<String, Object>>> okWithStore(Task task) {
        Map<String, Object> body = toBody(task);
        if (task.storeId() == null) {
            return Mono.just(ResponseEntity.ok(Map.of("success", true, "data", body)));
        }
        return storeEnrichment.loadStoreBlocks(List.of(task.storeId()))
                .map(stores -> {
                    Map<String, Object> block = stores.get(task.storeId());
                    if (block != null) {
                        body.put("store", block);
                    }
                    return ResponseEntity.ok(Map.of("success", true, "data", body));
                });
    }

    private Mono<Integer> visibleRecommenderLevel(Caller caller) {
        return reputationService.snapshot(caller.accountId())
                .map(snapshot -> snapshot.evaluation().effectiveLevel().number());
    }

    /**
     * 发布闸门 2-5（tier / 资金权限 / 单笔上限 / 活跃额度 / 月度额度）。immediate-create 内联同款；draft→publish 复用。
     * 闸门 1（org 归属）由 {@link #loadOwnedTask} 隐含（owner 必属该 org）。
     */
    private Mono<Void> enforcePublishGates(String organizationId, String permissionTier, Long bountyCents) {
        return publishGate.enforce(organizationId, permissionTier, bountyCents);
    }

    /** 组织级任务沿用 owner 管理；门店任务允许该店 MANAGER 管理。 */
    private Mono<Task> loadManageableTask(String taskId, Caller caller, String requiredStatus) {
        return loadManageableTaskAccess(taskId, caller, requiredStatus)
                .map(TaskResourceAuthorization.ManagedTask::task);
    }

    private Mono<TaskResourceAuthorization.ManagedTask> loadManageableTaskAccess(
            String taskId, Caller caller, String requiredStatus) {
        return taskAuthorization.requireManager(taskId, caller)
                .filter(access -> requiredStatus == null || requiredStatus.equals(access.task().status()))
                .switchIfEmpty(fail(409, "任务当前状态不允许该操作"));
    }

    private EventEnvelope taskPublishedEnvelope(Task task) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.id());
        payload.put("organizationId", task.organizationId());
        payload.put("ownerAccountId", task.ownerAccountId());
        payload.put("title", task.title());
        payload.put("version", task.version());
        if (task.storeId() != null) {
            payload.put("storeId", task.storeId());
        }
        if (task.applicationDeadline() != null) {
            payload.put("applicationDeadline", task.applicationDeadline().toString());
        }
        return new EventEnvelope(UUID.randomUUID().toString(), "TaskPublished", "Task",
                task.id(), task.version(), Instant.now(), null, payload);
    }

    /** Reviewer rejection is a merchant-facing event; identity resolves only the owner in this payload. */
    private EventEnvelope taskRejectedEnvelope(Task task, String note) {
        Map<String, Object> payload = taskEventPayload(task, true);
        payload.put("reason", note);
        payload.put("status", "draft");
        return new EventEnvelope(UUID.randomUUID().toString(), "TaskReviewRejected", "Task",
                task.id(), task.version(), Instant.now(), null, payload);
    }

    private EventEnvelope taskDraftUpdatedEnvelope(Task task) {
        return new EventEnvelope(UUID.randomUUID().toString(), "TaskDraftUpdated", "Task",
                task.id(), task.version(), Instant.now(), null,
                taskEventPayload(task, false));
    }

    private EventEnvelope taskClosedEnvelope(Task task) {
        return new EventEnvelope(UUID.randomUUID().toString(), "TaskClosed", "Task",
                task.id(), task.version(), Instant.now(), null,
                taskEventPayload(task, false));
    }

    private EventEnvelope taskCancelledEnvelope(Task task) {
        return new EventEnvelope(UUID.randomUUID().toString(), "TaskCancelled", "Task",
                task.id(), task.version(), Instant.now(), null,
                taskEventPayload(task, false));
    }

    /**
     * D-03 §5 cancel 退款事件：商家取消任务，已 accept 未提交凭证的 engagement 全额返还商家。
     * 确定性 eventId（type-3 {@code EngagementRefundedOnCancel:<appId>}）保证重跑 exactly-once；
     * reason={@code merchant_cancel} 供 trust 声誉消费（违约计数，D-05）。双方收件（identity 通知中心）。
     */
    private EventEnvelope engagementRefundedEnvelope(Task task, TaskApplication app) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.id());
        payload.put("applicationId", app.id());
        payload.put("organizationId", task.organizationId());
        payload.put("recommenderAccountId", app.recommenderAccountId());
        payload.put("taskOwnerId", task.ownerAccountId());
        payload.put("reason", "merchant_cancel");
        String eventId = UUID.nameUUIDFromBytes(
                ("EngagementRefundedOnCancel:" + app.id()).getBytes(StandardCharsets.UTF_8)).toString();
        return new EventEnvelope(eventId, "EngagementRefundedOnCancel", "TaskApplication",
                app.id(), 1, Instant.now(), null, payload);
    }

    /** cancel 响应：附 refundedCount（已退还的未提交履约数）。 */
    private Map<String, Object> cancelBody(Task task, int refundedCount) {
        Map<String, Object> m = toBody(task);
        m.put("refundedCount", refundedCount);
        return m;
    }

    private EventEnvelope taskRevisedEnvelope(Task task) {
        return new EventEnvelope(UUID.randomUUID().toString(), "TaskRevised", "Task",
                task.id(), task.version(), Instant.now(), null,
                taskEventPayload(task, false));
    }

    private static Map<String, Object> taskEventPayload(Task task, boolean includeTitle) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.id());
        payload.put("organizationId", task.organizationId());
        payload.put("ownerAccountId", task.ownerAccountId());
        payload.put("version", task.version());
        if (task.storeId() != null) {
            payload.put("storeId", task.storeId());
        }
        if (includeTitle) {
            payload.put("title", task.title());
        }
        return payload;
    }

    private Map<String, Object> toBody(Task task) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", task.id());
        m.put("ownerAccountId", task.ownerAccountId());
        m.put("organizationId", task.organizationId());
        if (task.storeId() != null) {
            m.put("storeId", task.storeId());
        }
        m.put("title", task.title());
        m.put("description", task.description());
        m.put("status", task.status());
        m.put("contentForm", task.contentForm());
        m.put("platform", task.platform());
        m.put("maxSlots", task.maxSlots());
        m.put("bountyCents", task.bountyCents());
        m.put("minRecommenderLevel", task.minRecommenderLevel());
        m.put("requirements", task.requirements());
        m.put("version", task.version());
        m.put("applicationDeadline", task.applicationDeadline() == null ? null : task.applicationDeadline().toString());
        m.put("autoAcceptMinLevel", task.autoAcceptMinLevel());
        m.put("publishedAt", task.publishedAt() == null ? null : task.publishedAt().toString());
        m.put("cancelledAt", task.cancelledAt() == null ? null : task.cancelledAt().toString());
        m.put("createdAt", task.createdAt() == null ? null : task.createdAt().toString());
        return m;
    }

    private static <T> Mono<T> fail(int status, String message) {
        return Mono.error(new MarketplaceException(status, message));
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private Mono<List<Map<String, Object>>> enrichTasks(List<Task> rows) {
        return metrics.findProgressByTaskIds(rows.stream().map(Task::id).toList())
                .collectMap(TaskProgress::taskId)
                .map(progress -> rows.stream().map(task -> {
                    Map<String, Object> body = toBody(task);
                    TaskProgress facts = progress.getOrDefault(task.id(), TaskProgress.empty(task.id()));
                    body.put("progress", progressBody(task, facts));
                    return body;
                }).toList());
    }

    private static Map<String, Object> progressBody(Task task, TaskProgress facts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totalApplications", facts.totalApplications());
        body.put("pendingApplications", facts.pendingApplications());
        body.put("reservingApplications", facts.reservingApplications());
        body.put("acceptedApplications", facts.acceptedApplications());
        body.put("rejectedApplications", facts.rejectedApplications());
        body.put("withdrawnApplications", facts.withdrawnApplications());
        body.put("refundedApplications", facts.refundedApplications());
        body.put("occupiedSlots", facts.occupiedSlots());
        body.put("maxSlots", task.maxSlots());
        body.put("remainingSlots", task.maxSlots() == null
                ? null : Math.max(0, task.maxSlots() - facts.occupiedSlots()));
        body.put("submittedDeliverables", facts.submittedDeliverables());
        body.put("confirmedDeliverables", facts.confirmedDeliverables());
        body.put("settledEngagements", facts.settledEngagements());
        body.put("reservedBountyCents", facts.reservedBountyCents());
        body.put("settledBountyCents", facts.settledBountyCents());
        return body;
    }

    private static Map<String, Object> dashboardBody(MerchantDashboard dashboard, BusinessReport report) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", dashboard.organizationId());
        body.put("storeId", dashboard.storeId());
        body.put("taskCount", dashboard.taskCount());
        body.put("publishedTaskCount", dashboard.publishedTaskCount());
        body.put("totalApplications", dashboard.totalApplications());
        body.put("acceptedApplications", dashboard.acceptedApplications());
        body.put("confirmedDeliverables", dashboard.confirmedDeliverables());
        body.put("settledEngagements", dashboard.settledEngagements());
        body.put("reservedBountyCents", dashboard.reservedBountyCents());
        body.put("settledBountyCents", dashboard.settledBountyCents());
        body.put("applicationAcceptanceRate", dashboard.applicationAcceptanceRate());
        body.put("averageRating", dashboard.averageRating());
        var attribution = report.attribution();
        Map<String, Object> marketing = new LinkedHashMap<>();
        marketing.put("exposureCollected", attribution.exposures() > 0);
        marketing.put("interactionCollected", attribution.interactions() > 0);
        marketing.put("conversionCollected", attribution.conversions() > 0);
        marketing.put("exposures", attribution.exposures());
        marketing.put("interactions", attribution.interactions());
        marketing.put("conversions", attribution.conversions());
        marketing.put("attributedRevenueCents", attribution.attributedRevenueCents());
        marketing.put("attributedRefundCents", attribution.attributedRefundCents());
        marketing.put("dataQuality", attribution.dataQuality());
        marketing.put("status", attribution.status());
        marketing.put("roi", attribution.roi() == null ? "unavailable" : attribution.roi());
        marketing.put("roiFormula", "(attributedRevenue-attributedRefund-settledBounty)/settledBounty");
        body.put("marketingMetrics", marketing);
        body.put("businessMetrics", Map.of(
                "orders", report.orders(), "paidOrders", report.paidOrders(),
                "redeemedOrders", report.redeemedOrders(), "refundedOrders", report.refundedOrders(),
                "grossGmvCents", report.grossGmvCents(), "refundedGmvCents", report.refundedGmvCents(),
                "netGmvCents", report.netGmvCents(), "merchantRevenueCents", report.merchantRevenueCents(),
                "platformFeeCents", report.platformFeeCents(), "recommenderRevenueCents", report.recommenderRevenueCents()));
        return body;
    }

    private static Map<String, Object> reviewBody(TaskReviewRepository.TaskReviewEntry entry) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", entry.id());
        body.put("taskId", entry.taskId());
        body.put("action", entry.action());
        body.put("reviewerAccountId", entry.reviewerAccountId());
        body.put("note", entry.note());
        body.put("createdAt", entry.createdAt() == null ? null : entry.createdAt().toString());
        return body;
    }

    /**
     * feed keyset 游标（GL-P1-TASK-001 Stage 2）：opaque base64url 编码 {@code createdAt|id}。
     * 坏游标 → decode 返回 null（当首页，不报错），避免前端持有过期游标时硬失败。
     */
    record FeedCursor(Instant ts, String id) {
        static String encode(Task task) {
            String raw = task.createdAt().toString() + "|" + task.id();
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static FeedCursor decode(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                int sep = raw.lastIndexOf('|');
                if (sep <= 0 || sep == raw.length() - 1) {
                    return null;
                }
                return new FeedCursor(Instant.parse(raw.substring(0, sep)), raw.substring(sep + 1));
            } catch (Exception error) {
                return null;
            }
        }
    }
}
