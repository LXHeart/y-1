package com.grassland.marketplace.workflow;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * demo workflow HTTP 入口。
 *
 * <ul>
 *   <li>POST /workflow/demo — 非阻塞启动 workflow，返回 workflowId。</li>
 *   <li>GET /workflow/demo/{id} — 取结果（阻塞至完成，demo workflow 秒级完成）。</li>
 * </ul>
 *
 * <p>{@link WorkflowClient} 由 temporal-spring-boot-starter 在 test-server 与真实模式均提供，
 * 故 controller 总是装配。阻塞的 SDK 调用用 {@code boundedElastic} 包裹避免卡事件循环。
 */
@RestController
@RequestMapping("/workflow")
public class DemoWorkflowController {

    private final WorkflowClient workflowClient;

    public DemoWorkflowController(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    @PostMapping("/demo")
    public Mono<Map<String, String>> start(@RequestBody Map<String, Object> body) {
        String seed = String.valueOf(body.getOrDefault("seed", "default"));
        int sleepSeconds = parseInt(body.get("sleepSeconds"), 1);
        String workflowId = "demo-" + UUID.randomUUID();
        GrasslandDemoWorkflow stub = workflowClient.newWorkflowStub(
                GrasslandDemoWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(GrasslandDemoWorkflowImpl.TASK_QUEUE)
                        .build());
        return Mono.fromRunnable(() -> WorkflowClient.start(stub::run, seed, sleepSeconds))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(Map.of("workflowId", workflowId));
    }

    @GetMapping("/demo/{id}")
    public Mono<Map<String, String>> result(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            GrasslandDemoWorkflow stub = workflowClient.newWorkflowStub(GrasslandDemoWorkflow.class, id);
            String r = WorkflowStub.fromTyped(stub).getResult(String.class);
            return Map.of("workflowId", id, "result", r);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static int parseInt(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }
}
