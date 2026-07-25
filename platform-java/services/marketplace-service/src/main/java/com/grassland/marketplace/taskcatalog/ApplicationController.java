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
    private final WorkflowClient workflowClient;
    private final long settlementWindowSeconds;

    public ApplicationController(MarketplaceCallerResolver callers, TaskRepository tasks,
                                 TaskApplicationRepository apps, OutboxRepository outbox,
                                 WorkflowClient workflowClient,
                                 @Value("${marketplace.settlement.window-seconds:5}") long settlementWindowSeconds) {
        this.callers = callers;
        this.tasks = tasks;
        this.apps = apps;
        this.outbox = outbox;
        this.workflowClient = workflowClient;
        this.settlementWindowSeconds = settlementWindowSeconds;
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
                                            .switchIfEmpty(apps.create(id, rec.accountId(), note)
                                                    .switchIfEmpty(Mono.error(new MarketplaceException(409, "已报名该任务")))));
                        })
                        .flatMap(app -> outbox.append(envelope("ApplicationSubmitted", app, null)).thenReturn(app))
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
        return apps.accept(app.id(), app.taskId(), merchant.accountId())
                .switchIfEmpty(fail(409, "该报名已处理"))
                .flatMap(a -> outbox.append(envelope("ApplicationAccepted", a, task.ownerAccountId())).thenReturn(a))
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

    @PostMapping("/api/tasks/{id}/applications/{appId}/confirm")
    public Mono<ResponseEntity<Map<String, Object>>> confirm(@PathVariable String id, @PathVariable String appId,
                                                             ServerHttpRequest request) {
        return callers.requireMerchant(request)
                .flatMap(merchant -> loadOwnedTask(id, merchant.accountId())
                        .flatMap(task -> loadAcceptedApp(id, appId)
                                .flatMap(app -> apps.confirm(appId, id)
                                        .switchIfEmpty(fail(409, "该报名未接受或已确认"))
                                        .flatMap(confirmed -> outbox
                                                .append(envelope("MerchantConfirmed", confirmed, task.ownerAccountId()))
                                                .thenReturn(confirmed)))
                                .flatMap(app -> startSettlementWorkflow(task, app))));
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

    /** 映射 application 为结算结局：未确认→not_confirmed；有 settled/held 事件→对应；否则 settling。 */
    private Mono<ResponseEntity<Map<String, Object>>> settlementOutcome(TaskApplication app) {
        if (app.confirmedAt() == null) {
            return Mono.just(ok(Map.of("status", "not_confirmed")));
        }
        return outbox.latestSettlementStatus(app.id())
                .map(this::ok)  // {status, reason?}
                .defaultIfEmpty(ok(Map.of("status", "settling")));
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
                            return apps.withdraw(appId, id, rec.accountId())
                                    .switchIfEmpty(fail(409, "该报名已处理"));
                        })
                        .flatMap(app -> outbox.append(envelope("ApplicationWithdrawn", app, null)).thenReturn(app))
                        .map(app -> ResponseEntity.ok(Map.of("success", true, "data", toBody(app)))));
    }

    @GetMapping("/api/tasks/{id}/applications")
    public Mono<ResponseEntity<Map<String, Object>>> list(@PathVariable String id, ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> loadOwnedTask(id, caller.accountId())
                        .flatMap(task -> apps.findByTaskId(id).collectList()
                                .map(list -> ResponseEntity.ok(Map.of("success", true,
                                        "data", list.stream().map(this::toBody).toList())))));
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
