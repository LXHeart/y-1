package com.grassland.intelligence.ai.controlplane;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 平台模型控制面 admin API（model-control-plane，HLD §12.3；GL-P3-AI-001）。
 *
 * <p>端点（全部 {@code requireAdmin}——平台后台角色，断言 {@code role=admin}）：
 * <ul>
 *   <li>GET /api/admin/ai/models — 列出当前有效配置</li>
 *   <li>GET /api/admin/ai/models/{capability}/{modelRole} — 取某主/备当前配置</li>
 *   <li>POST /api/admin/ai/models — 创建（version=1）；(capability,modelRole) 已存在 → 409</li>
 *   <li>PUT /api/admin/ai/models/{capability}/{modelRole} — 修订（version+1 + disable 旧 + history）</li>
 *   <li>DELETE /api/admin/ai/models/{capability}/{modelRole} — 禁用（+ history）</li>
 * </ul>
 *
 * <p>非 KEK 门控：平台模型凭据在 env/secret（非此表），本 controller 无密钥，故不挂
 * {@code CryptoKekConfiguredCondition}。
 */
@RestController
@RequestMapping("/api/admin/ai/models")
public class PlatformModelConfigController {

    private final IntelligenceCallerResolver callers;
    private final PlatformModelConfigRepository repository;
    private final TransactionalOperator transactions;
    private final PlatformProviderPolicy providerPolicy;

    public PlatformModelConfigController(
            IntelligenceCallerResolver callers,
            PlatformModelConfigRepository repository,
            TransactionalOperator transactions,
            PlatformProviderPolicy providerPolicy) {
        this.callers = callers;
        this.repository = repository;
        this.transactions = transactions;
        this.providerPolicy = providerPolicy;
    }

    @GetMapping
    public Flux<PlatformModelConfigResponse> list(ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMapMany(c -> repository.findAllCurrent().map(PlatformModelConfigResponse::from));
    }

    @GetMapping("/{capability}/{modelRole}")
    public Mono<ResponseEntity<PlatformModelConfigResponse>> get(
            @PathVariable String capability, @PathVariable String modelRole, ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(c -> repository.findCurrent(capability, modelRole)
                        .map(cfg -> ResponseEntity.ok(PlatformModelConfigResponse.from(cfg)))
                        .switchIfEmpty(Mono.error(notFound(capability, modelRole))));
    }

    @PostMapping
    public Mono<ResponseEntity<PlatformModelConfigResponse>> create(
            @Valid @RequestBody CreatePlatformModelRequest body, ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(admin -> repository.findCurrent(body.capability(), body.modelRole())
                        .flatMap(existing -> Mono.<PlatformModelConfig>error(new IntelligenceException(409,
                                "该能力+角色已有平台模型配置，请改用 PUT 修订")))
                        .switchIfEmpty(Mono.just(buildForCreate(body)))
                        .flatMap(cfg -> transactions.transactional(repository.create(cfg, admin.accountId())
                                .then(repository.findCurrent(body.capability(), body.modelRole()))))
                        .map(saved -> ResponseEntity.status(201).body(PlatformModelConfigResponse.from(saved))))
                .onErrorResume(DataIntegrityViolationException.class,
                        e -> Mono.error(new IntelligenceException(409, "该能力+角色已有平台模型配置")));
    }

    @PutMapping("/{capability}/{modelRole}")
    public Mono<ResponseEntity<PlatformModelConfigResponse>> revise(
            @PathVariable String capability,
            @PathVariable String modelRole,
            @Valid @RequestBody UpdatePlatformModelRequest body,
            ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(admin -> transactions.transactional(
                        repository.revise(capability, modelRole, buildForUpdate(capability, modelRole, body), admin.accountId()))
                        .map(saved -> ResponseEntity.ok(PlatformModelConfigResponse.from(saved)))
                        .switchIfEmpty(Mono.error(notFound(capability, modelRole))));
    }

    @DeleteMapping("/{capability}/{modelRole}")
    public Mono<ResponseEntity<Void>> disable(
            @PathVariable String capability, @PathVariable String modelRole, ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(admin -> transactions.transactional(repository.disable(capability, modelRole, admin.accountId()))
                        .flatMap(ok -> ok
                                ? Mono.just(ResponseEntity.noContent().<Void>build())
                                : Mono.error(notFound(capability, modelRole))));
    }

    private PlatformModelConfig buildForCreate(CreatePlatformModelRequest body) {
        providerPolicy.validate(body.provider(), body.baseUrl());
        return new PlatformModelConfig(null, body.capability(), body.modelRole(), body.provider(), body.model(),
                body.baseUrl(), body.maxConcurrency(), healthOrDefault(body.healthStatus()), true, 1, null, null, null);
    }

    private PlatformModelConfig buildForUpdate(String capability, String modelRole, UpdatePlatformModelRequest body) {
        providerPolicy.validate(body.provider(), body.baseUrl());
        return new PlatformModelConfig(null, capability, modelRole, body.provider(), body.model(), body.baseUrl(),
                body.maxConcurrency(), healthOrDefault(body.healthStatus()), true, 1, null, null, null);
    }

    private static String healthOrDefault(String healthStatus) {
        return healthStatus != null ? healthStatus : PlatformModelConfig.HEALTH_HEALTHY;
    }

    private static IntelligenceException notFound(String capability, String modelRole) {
        return new IntelligenceException(404, "未找到平台模型配置: " + capability + "/" + modelRole);
    }
}
