package com.grassland.finance.provider;

import com.grassland.finance.security.FinanceCallerResolver;
import com.grassland.identity.assertion.BackendRole;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Finance-only controls for Sandbox webhook simulation and provider reconciliation. */
@RestController
public class ProviderLifecycleController {

    private final FinanceCallerResolver callers;
    private final ProviderLifecycleService service;
    private final ProviderOperationRepository operations;
    private final ProviderLifecycleRepository lifecycle;

    public ProviderLifecycleController(
            FinanceCallerResolver callers, ProviderLifecycleService service,
            ProviderOperationRepository operations, ProviderLifecycleRepository lifecycle) {
        this.callers = callers;
        this.service = service;
        this.operations = operations;
        this.lifecycle = lifecycle;
    }

    @PostMapping("/api/admin/finance/sandbox/webhooks")
    public Mono<ResponseEntity<Map<String, Object>>> simulateWebhook(
            @RequestBody ProviderWebhookCommand body, ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.FINANCE)
                .then(Mono.fromRunnable(() -> requireSandbox(body == null ? null : body.provider())))
                .then(service.receiveWebhook(body))
                .map(value -> ResponseEntity.ok(Map.of("success", true, "data", value)));
    }

    @PostMapping("/api/admin/finance/sandbox/reconciliation")
    public Mono<ResponseEntity<Map<String, Object>>> importSandboxStatement(
            @RequestBody ProviderReconciliationCommand body, ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.FINANCE)
                .then(Mono.fromRunnable(() -> requireSandbox(body == null ? null : body.provider())))
                .then(service.reconcile(body))
                .map(value -> ResponseEntity.ok(Map.of("success", true, "data", value)));
    }

    @GetMapping("/api/admin/finance/provider-operations")
    public Mono<ResponseEntity<Map<String, Object>>> listOperations(
            @RequestParam(defaultValue = "50") int limit, ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.FINANCE)
                .thenMany(operations.list(limit)).collectList()
                .map(value -> ResponseEntity.ok(Map.of("success", true, "data", value)));
    }

    @GetMapping("/api/admin/finance/provider-webhooks")
    public Mono<ResponseEntity<Map<String, Object>>> listWebhooks(
            @RequestParam(defaultValue = "50") int limit, ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.FINANCE)
                .thenMany(lifecycle.listWebhooks(limit)).collectList()
                .map(value -> ResponseEntity.ok(Map.of("success", true, "data", value)));
    }

    @GetMapping("/api/admin/finance/provider-reconciliation")
    public Mono<ResponseEntity<Map<String, Object>>> listReconciliation(
            @RequestParam(defaultValue = "50") int limit, ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.FINANCE)
                .thenMany(lifecycle.listReconciliations(limit)).collectList()
                .map(value -> ResponseEntity.ok(Map.of("success", true, "data", value)));
    }

    private static void requireSandbox(String provider) {
        if (!"sandbox".equalsIgnoreCase(provider)) {
            throw new IllegalArgumentException("Sandbox 模拟端点仅接受 provider=sandbox");
        }
    }
}
