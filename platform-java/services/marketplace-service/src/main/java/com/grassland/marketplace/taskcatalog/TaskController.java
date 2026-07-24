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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * task-catalog HTTP 入口。草场 Epic 4 Slice 4A（HLD 5.3）。
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
                .flatMap(merchant -> tasks.create(merchant.accountId(), body.organizationId(), body.title(),
                                body.description(), body.contentForm(), body.platform())
                        .flatMap(task -> outbox.append(new EventEnvelope(
                                UUID.randomUUID().toString(), "TaskPublished", "Task",
                                task.id(), 1, Instant.now(), null,
                                Map.of("taskId", task.id(), "organizationId", task.organizationId(),
                                        "ownerAccountId", task.ownerAccountId(), "title", task.title())))
                                .thenReturn(task))
                        .map(task -> ResponseEntity.status(201).body(Map.of("success", true, "data", toBody(task)))));
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

    @GetMapping("/api/tasks/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> get(@PathVariable String id, ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> tasks.findById(id)
                        .map(task -> ResponseEntity.ok(Map.of("success", true, "data", toBody(task))))
                        .switchIfEmpty(Mono.error(new MarketplaceException(404, "任务不存在"))));
    }

    @ExceptionHandler(MarketplaceException.class)
    public ResponseEntity<Map<String, Object>> handleError(MarketplaceException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    /** 非法请求体（缺 organizationId/title）→ 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadInput(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "error", error.getMessage()));
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
        m.put("createdAt", task.createdAt() == null ? null : task.createdAt().toString());
        return m;
    }
}
