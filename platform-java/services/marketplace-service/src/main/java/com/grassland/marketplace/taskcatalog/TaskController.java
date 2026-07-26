package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * task-catalog HTTP 入口。草场 Epic 4 Slice 4A（HLD 5.3；4B 名额/限额；4F 赏金）。
 *
 * <ul>
 *   <li>POST /api/tasks — 商家发布任务（断言 caller 须 merchant；owner=caller；organizationId 取请求体；
 *       创建即 published；outbox {@code TaskPublished}）。</li>
 *   <li>GET /api/tasks?organizationId=&status= — 列任务大厅（默认 published；任意已登录 caller）。</li>
 *   <li>GET /api/tasks/{id} — 任务详情（不存在 404）。</li>
 * </ul>
 *
 * <p>身份靠 {@link MarketplaceCallerResolver} 消费 BFF 断言。资源级授权（merchant 确属该 org）留 4B+。
 */
@RestController
public class TaskController {

    private final MarketplaceCallerResolver callers;
    private final TaskRepository tasks;
    private final OutboxRepository outbox;

    public TaskController(MarketplaceCallerResolver callers, TaskRepository tasks, OutboxRepository outbox) {
        this.callers = callers;
        this.tasks = tasks;
        this.outbox = outbox;
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
                                    : tasks.create(merchant.accountId(), body.organizationId(), body.title(),
                                            body.description(), body.contentForm(), body.platform(), body.maxSlots(),
                                            body.bountyCents()));
                })
                .flatMap(task -> outbox.append(new EventEnvelope(
                        UUID.randomUUID().toString(), "TaskPublished", "Task",
                        task.id(), 1, Instant.now(), null,
                        Map.of("taskId", task.id(), "organizationId", task.organizationId(),
                                "ownerAccountId", task.ownerAccountId(), "title", task.title())))
                        .thenReturn(task))
                .map(task -> ResponseEntity.status(201).body(Map.of("success", true, "data", toBody(task))));
    }

    @GetMapping("/api/tasks")
    public Mono<ResponseEntity<Map<String, Object>>> list(@RequestParam String organizationId,
                                                          @RequestParam(required = false, defaultValue = "published") String status,
                                                          ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> tasks.findByOrganization(organizationId, status).collectList()
                        .map(list -> ResponseEntity.ok(Map.of("success", true,
                                "data", list.stream().map(this::toBody).toList()))));
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
                        .map(task -> ResponseEntity.ok(Map.of("success", true, "data", toBody(task))))
                        .switchIfEmpty(Mono.error(new MarketplaceException(404, "任务不存在"))));
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
        m.put("createdAt", task.createdAt() == null ? null : task.createdAt().toString());
        return m;
    }
}
