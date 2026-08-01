package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;

    public TaskController(MarketplaceCallerResolver callers, TaskRepository tasks, OutboxRepository outbox,
                          TransactionalOperator transactions) {
        this.callers = callers;
        this.tasks = tasks;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    @PostMapping(value = "/api/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> create(@RequestBody CreateTaskRequest body, ServerHttpRequest request) {
        return callers.requireMerchant(request)
                .flatMap(merchant -> {
                    // 闸门 1：org 归属自查——发布 org 须等于 caller 断言的 org（HLD 7.4，不只信 body 声明）。
                    if (!body.organizationId().equals(merchant.organizationId())) {
                        return Mono.<Task>error(new MarketplaceException(403, "无权为该组织发布任务"));
                    }
                    // 闸门 2：tier 须允许发布（DRAFT=0 → 403）。
                    MerchantTier tier = MerchantTier.fromDb(merchant.permissionTier());
                    int maxActive = PublishQuotaPolicy.maxActiveTasks(tier);
                    if (maxActive == 0) {
                        return Mono.<Task>error(new MarketplaceException(403, "当前等级不可发布任务"));
                    }
                    // 闸门 3（D-05）：资金型任务须有交易权限，且赏金不超单笔上限。
                    long bounty = body.bountyCents() == null ? 0L : body.bountyCents();
                    long maxTx = PublishQuotaPolicy.maxTxAmountCents(tier);
                    if (bounty > 0 && maxTx == 0) {
                        return Mono.<Task>error(new MarketplaceException(403, "当前等级不可发布资金型任务"));
                    }
                    if (bounty > maxTx) {
                        return Mono.<Task>error(new MarketplaceException(409, "赏金超出本组织单笔上限"));
                    }
                    // 闸门 4+5（D-05）：活跃任务数上限 + 本月新建任务数上限 → 409。
                    int maxMonthly = PublishQuotaPolicy.maxMonthlyTasks(tier);
                    return tasks.countActiveByOrganization(merchant.organizationId())
                            .flatMap(active -> active >= maxActive
                                    ? Mono.<Integer>error(new MarketplaceException(409, "已达本组织发布上限"))
                                    : tasks.countCreatedThisMonthByOrganization(merchant.organizationId()))
                            .flatMap(monthly -> monthly >= maxMonthly
                                    ? Mono.<Task>error(new MarketplaceException(409, "已达本组织本月发布上限"))
                                    : transactions.transactional(
                                            tasks.create(merchant.accountId(), body.organizationId(), body.title(),
                                                    body.description(), body.contentForm(), body.platform(), body.maxSlots(),
                                                    body.bountyCents(), body.applicationDeadline())
                                                    .flatMap(task -> outbox.append(taskPublishedEnvelope(task)).thenReturn(task))));
                })
                .map(task -> ResponseEntity.status(201).body(Map.of("success", true, "data", toBody(task))));
    }

    /** 创建草稿。draft 创建不占发布额度、不需资金权限（草稿 tier 也可建）。 */
    @PostMapping(value = "/api/tasks/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> createDraft(@RequestBody CreateDraftRequest body, ServerHttpRequest request) {
        return callers.requireMerchant(request)
                .flatMap(merchant -> {
                    if (!body.organizationId().equals(merchant.organizationId())) {
                        return Mono.<Task>error(new MarketplaceException(403, "无权为该组织创建任务"));
                    }
                    return transactions.transactional(
                            tasks.createDraft(merchant.accountId(), body.organizationId(), body.title(),
                                    body.description(), body.contentForm(), body.platform(), body.maxSlots(),
                                    body.bountyCents(), body.applicationDeadline()));
                })
                .map(task -> ResponseEntity.status(201).body(Map.of("success", true, "data", toBody(task))));
    }

    /** 编辑草稿（仅 draft 态；owner；expectedVersion 乐观锁）。 */
    @PutMapping(value = "/api/tasks/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> update(@PathVariable String id, @RequestBody UpdateTaskRequest body,
                                                            ServerHttpRequest request) {
        return callers.requireMerchant(request)
                .flatMap(merchant -> loadOwnedTask(id, merchant.accountId(), "draft")
                        .flatMap(ignored -> transactions.transactional(
                                tasks.updateDraft(id, body.expectedVersion(), body.title(), body.description(),
                                        body.contentForm(), body.platform(), body.maxSlots(), body.bountyCents(),
                                        body.applicationDeadline())
                                        .switchIfEmpty(Mono.error(new MarketplaceException(409, "任务已变更，请刷新后重试")))
                                        .flatMap(task -> outbox.append(taskDraftUpdatedEnvelope(task)).thenReturn(task)))))
                .map(task -> ResponseEntity.ok(Map.of("success", true, "data", toBody(task))));
    }

    /** 发布草稿（owner；tier/额度/资金闸门；落快照；outbox TaskPublished）。 */
    @PostMapping(value = "/api/tasks/{id}/publish", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> publish(@PathVariable String id, @RequestBody TaskLifecycleRequest body,
                                                             ServerHttpRequest request) {
        return callers.requireMerchant(request)
                .flatMap(merchant -> loadOwnedTask(id, merchant.accountId(), "draft")
                        .flatMap(draft -> enforcePublishGates(merchant, draft.bountyCents())
                                .thenReturn(draft)
                                .flatMap(ignored -> transactions.transactional(
                                        tasks.publish(id, body.expectedVersion(), merchant.accountId())
                                                .switchIfEmpty(Mono.error(new MarketplaceException(409, "任务已变更，请刷新后重试")))
                                                .flatMap(task -> outbox.append(taskPublishedEnvelope(task)).thenReturn(task))))))
                .map(task -> ResponseEntity.ok(Map.of("success", true, "data", toBody(task))));
    }

    /** 关闭报名（published→closed；owner；expectedVersion）。 */
    @PostMapping("/api/tasks/{id}/close")
    public Mono<ResponseEntity<Map<String, Object>>> close(@PathVariable String id, @RequestBody TaskLifecycleRequest body,
                                                           ServerHttpRequest request) {
        return callers.requireMerchant(request)
                .flatMap(merchant -> loadOwnedTask(id, merchant.accountId(), "published")
                        .flatMap(ignored -> transactions.transactional(
                                tasks.close(id, body.expectedVersion())
                                        .switchIfEmpty(Mono.error(new MarketplaceException(409, "任务已变更，请刷新后重试")))
                                        .flatMap(task -> outbox.append(taskClosedEnvelope(task)).thenReturn(task)))))
                .map(task -> ResponseEntity.ok(Map.of("success", true, "data", toBody(task))));
    }

    /** 取消任务（draft|published→cancelled；owner；expectedVersion）。 */
    @PostMapping("/api/tasks/{id}/cancel")
    public Mono<ResponseEntity<Map<String, Object>>> cancel(@PathVariable String id, @RequestBody TaskLifecycleRequest body,
                                                            ServerHttpRequest request) {
        return callers.requireMerchant(request)
                .flatMap(merchant -> loadOwnedTask(id, merchant.accountId(), null)
                        .flatMap(owned -> {
                            String status = owned.status();
                            if (!TaskStatus.DRAFT.dbValue().equals(status) && !TaskStatus.PUBLISHED.dbValue().equals(status)) {
                                return Mono.<Task>error(new MarketplaceException(409, "任务已结束，不可取消"));
                            }
                            return transactions.transactional(
                                    tasks.cancel(id, body.expectedVersion())
                                            .switchIfEmpty(Mono.error(new MarketplaceException(409, "任务已变更，请刷新后重试")))
                                            .flatMap(task -> outbox.append(taskCancelledEnvelope(task)).thenReturn(task)));
                        }))
                .map(task -> ResponseEntity.ok(Map.of("success", true, "data", toBody(task))));
    }

    @GetMapping("/api/tasks")
    public Mono<ResponseEntity<Map<String, Object>>> list(@RequestParam String organizationId,
                                                          @RequestParam(required = false, defaultValue = "published") String status,
                                                          ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> {
                    // 非 published status 仅本 org merchant 可查（防跨组织草稿/取消泄露）。
                    String effectiveStatus = TaskStatus.PUBLISHED.dbValue().equalsIgnoreCase(status) || status.isBlank()
                            ? TaskStatus.PUBLISHED.dbValue()
                            : (caller.isMerchant() && organizationId.equals(caller.organizationId())
                                    ? status
                                    : TaskStatus.PUBLISHED.dbValue());
                    return tasks.findByOrganization(organizationId, effectiveStatus).collectList()
                            .map(list -> ResponseEntity.ok(Map.of("success", true,
                                    "data", list.stream().map(this::toBody).toList())));
                });
    }

    /**
     * 全局任务大厅（GL-P1-TASK-001 Stage 2）：跨组织 feed，仅 published 且未截止。
     *
     * <p>任意已登录 caller 可查（推荐官浏览大厅）。keyset 游标分页（{@code created_at DESC, id DESC}），
     * 筛选 platform/contentForm/minBountyCents。距离筛选未做（task 无地理位置字段，避免发明）。
     * 响应 {@code {items, nextCursor, hasMore}}，与按 org 的 {@code GET /api/tasks} 裸数组形状区分。
     * 路由字面量 {@code feed} 在 PathPattern 优先于 {@code {id}}，命中既有 {@code /api/tasks**} BFF flag。
     */
    @GetMapping("/api/tasks/feed")
    public Mono<ResponseEntity<Map<String, Object>>> feed(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String contentForm,
            @RequestParam(required = false) Long minBountyCents,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "20") int limit,
            ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> {
                    int safeLimit = Math.max(1, Math.min(limit, 50));
                    FeedCursor decoded = FeedCursor.decode(cursor);
                    TaskRepository.FeedFilter filter = new TaskRepository.FeedFilter(
                            blankToNull(platform), blankToNull(contentForm),
                            (minBountyCents == null || minBountyCents < 0) ? null : minBountyCents);
                    return tasks.findFeed(filter,
                                    decoded == null ? null : decoded.ts(),
                                    decoded == null ? null : decoded.id(),
                                    safeLimit + 1)
                            .collectList()
                            .map(rows -> feedBody(rows, safeLimit));
                });
    }

    /** 组装 feed 分页体：取 limit+1 判 hasMore，nextCursor 为本页最后一行的 (created_at, id)。 */
    private ResponseEntity<Map<String, Object>> feedBody(List<Task> rows, int limit) {
        boolean hasMore = rows.size() > limit;
        List<Task> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore && !page.isEmpty() ? FeedCursor.encode(page.get(page.size() - 1)) : null;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", page.stream().map(this::toBody).toList());
        data.put("nextCursor", nextCursor);
        data.put("hasMore", hasMore);
        return ResponseEntity.ok(Map.of("success", true, "data", data));
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
                    return tasks.countActiveByOrganization(organizationId)
                            .flatMap(active -> tasks.countCreatedThisMonthByOrganization(organizationId)
                                    .map(monthly -> ResponseEntity.ok(Map.of("success", true, "data", Map.of(
                                            "organizationId", organizationId,
                                            "activeTasks", active,
                                            "monthlyTasks", monthly)))));
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
                            if (publicVisible || caller.accountId().equals(task.ownerAccountId())) {
                                return Mono.just(ResponseEntity.ok(Map.of("success", true, "data", toBody(task))));
                            }
                            return Mono.error(new MarketplaceException(404, "任务不存在"));
                        }));
    }

    /**
     * 发布闸门 2-5（tier / 资金权限 / 单笔上限 / 活跃额度 / 月度额度）。immediate-create 内联同款；draft→publish 复用。
     * 闸门 1（org 归属）由 {@link #loadOwnedTask} 隐含（owner 必属该 org）。
     */
    private Mono<Void> enforcePublishGates(Caller merchant, Long bountyCents) {
        MerchantTier tier = MerchantTier.fromDb(merchant.permissionTier());
        int maxActive = PublishQuotaPolicy.maxActiveTasks(tier);
        if (maxActive == 0) {
            return Mono.error(new MarketplaceException(403, "当前等级不可发布任务"));
        }
        long bounty = bountyCents == null ? 0L : bountyCents;
        long maxTx = PublishQuotaPolicy.maxTxAmountCents(tier);
        if (bounty > 0 && maxTx == 0) {
            return Mono.error(new MarketplaceException(403, "当前等级不可发布资金型任务"));
        }
        if (bounty > maxTx) {
            return Mono.error(new MarketplaceException(409, "赏金超出本组织单笔上限"));
        }
        int maxMonthly = PublishQuotaPolicy.maxMonthlyTasks(tier);
        return tasks.countActiveByOrganization(merchant.organizationId())
                .flatMap(active -> active >= maxActive
                        ? Mono.<Integer>error(new MarketplaceException(409, "已达本组织发布上限"))
                        : tasks.countCreatedThisMonthByOrganization(merchant.organizationId()))
                .flatMap(monthly -> monthly >= maxMonthly
                        ? Mono.<Void>error(new MarketplaceException(409, "已达本组织本月发布上限"))
                        : Mono.empty());
    }

    /** 加载 owner 的任务；{@code requiredStatus} 非 null 时额外校验状态（否则 409）。不存在→404，非 owner→403。 */
    private Mono<Task> loadOwnedTask(String taskId, String callerAccountId, String requiredStatus) {
        return tasks.findById(taskId)
                .switchIfEmpty(fail(404, "任务不存在"))
                .filter(t -> callerAccountId.equals(t.ownerAccountId()))
                .switchIfEmpty(fail(403, "无权操作该任务"))
                .filter(t -> requiredStatus == null || requiredStatus.equals(t.status()))
                .switchIfEmpty(fail(409, "任务当前状态不允许该操作"));
    }

    private EventEnvelope taskPublishedEnvelope(Task task) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.id());
        payload.put("organizationId", task.organizationId());
        payload.put("ownerAccountId", task.ownerAccountId());
        payload.put("title", task.title());
        payload.put("version", task.version());
        if (task.applicationDeadline() != null) {
            payload.put("applicationDeadline", task.applicationDeadline().toString());
        }
        return new EventEnvelope(UUID.randomUUID().toString(), "TaskPublished", "Task",
                task.id(), task.version(), Instant.now(), null, payload);
    }

    private EventEnvelope taskDraftUpdatedEnvelope(Task task) {
        return new EventEnvelope(UUID.randomUUID().toString(), "TaskDraftUpdated", "Task",
                task.id(), task.version(), Instant.now(), null,
                Map.of("taskId", task.id(), "organizationId", task.organizationId(),
                        "ownerAccountId", task.ownerAccountId(), "version", task.version()));
    }

    private EventEnvelope taskClosedEnvelope(Task task) {
        return new EventEnvelope(UUID.randomUUID().toString(), "TaskClosed", "Task",
                task.id(), task.version(), Instant.now(), null,
                Map.of("taskId", task.id(), "organizationId", task.organizationId(),
                        "ownerAccountId", task.ownerAccountId(), "version", task.version()));
    }

    private EventEnvelope taskCancelledEnvelope(Task task) {
        return new EventEnvelope(UUID.randomUUID().toString(), "TaskCancelled", "Task",
                task.id(), task.version(), Instant.now(), null,
                Map.of("taskId", task.id(), "organizationId", task.organizationId(),
                        "ownerAccountId", task.ownerAccountId(), "version", task.version()));
    }

    private Map<String, Object> toBody(Task task) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", task.id());
        m.put("ownerAccountId", task.ownerAccountId());
        m.put("organizationId", task.organizationId());
        m.put("title", task.title());
        m.put("description", task.description());
        m.put("status", task.status());
        m.put("contentForm", task.contentForm());
        m.put("platform", task.platform());
        m.put("maxSlots", task.maxSlots());
        m.put("bountyCents", task.bountyCents());
        m.put("version", task.version());
        m.put("applicationDeadline", task.applicationDeadline() == null ? null : task.applicationDeadline().toString());
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
