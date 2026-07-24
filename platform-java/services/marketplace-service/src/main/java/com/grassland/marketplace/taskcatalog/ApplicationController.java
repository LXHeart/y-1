package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * application 聚合 HTTP 入口（草场 Epic 4 Slice 4B / HLD 5.3、10.2）。
 *
 * <ul>
 *   <li>POST /api/tasks/{id}/applications — 推荐官报名（requireRecommender；任务须 published；
 *       名额满 fail-fast 409；一人一报 409；outbox {@code ApplicationSubmitted}）。</li>
 *   <li>POST /api/tasks/{id}/applications/{appId}/accept — 商家接受（须任务 owner；名额 Java 层校验；
 *       pending 守卫条件 UPDATE；outbox {@code ApplicationAccepted}）。</li>
 *   <li>POST /api/tasks/{id}/applications/{appId}/reject — 商家拒绝（须任务 owner；outbox {@code ApplicationRejected}）。</li>
 *   <li>POST /api/tasks/{id}/applications/{appId}/withdraw — 推荐官撤销本人 pending（WHERE 含 recommender 自查；
 *       outbox {@code ApplicationWithdrawn}）。</li>
 *   <li>GET /api/tasks/{id}/applications — 任务 owner 列全部报名（非 owner 403）。</li>
 * </ul>
 *
 * <p>身份靠 {@link MarketplaceCallerResolver}（BFF 断言）；资源级自查：accept/reject 校验 caller==task.owner，
 * withdraw 把 recommender 烧进 WHERE（HLD 7.4）。名额并发：单 owner acceptor 使 Java 计数足够（多 acceptor 再加事务，TODO）。
 * 错误统一由全局 {@code MarketplaceErrorHandler} 处理。
 */
@RestController
public class ApplicationController {

    private final MarketplaceCallerResolver callers;
    private final TaskRepository tasks;
    private final TaskApplicationRepository apps;
    private final OutboxRepository outbox;

    public ApplicationController(MarketplaceCallerResolver callers, TaskRepository tasks,
                                 TaskApplicationRepository apps, OutboxRepository outbox) {
        this.callers = callers;
        this.tasks = tasks;
        this.apps = apps;
        this.outbox = outbox;
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
                                        ? Mono.<TaskApplication>error(new MarketplaceException(409, "名额已满"))
                                        : apps.accept(appId, id, merchant.accountId())
                                                .switchIfEmpty(Mono.error(new MarketplaceException(409, "该报名已处理")))))
                                .flatMap(app -> outbox
                                        .append(envelope("ApplicationAccepted", app, task.ownerAccountId()))
                                        .thenReturn(app)))
                        .map(app -> ResponseEntity.ok(Map.of("success", true, "data", toBody(app)))));
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
