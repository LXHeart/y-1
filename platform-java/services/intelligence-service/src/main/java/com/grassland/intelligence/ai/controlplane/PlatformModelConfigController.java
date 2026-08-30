package com.grassland.intelligence.ai.controlplane;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import jakarta.validation.Valid;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.RequestParam;
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
    private final PlatformProviderCredentialRepository credentials;
    private final TransactionalOperator transactions;
    private final PlatformProviderPolicy providerPolicy;

    public PlatformModelConfigController(
            IntelligenceCallerResolver callers,
            PlatformModelConfigRepository repository,
            PlatformProviderCredentialRepository credentials,
            TransactionalOperator transactions,
            PlatformProviderPolicy providerPolicy) {
        this.callers = callers;
        this.repository = repository;
        this.credentials = credentials;
        this.transactions = transactions;
        this.providerPolicy = providerPolicy;
    }

    /**
     * 归一「凭据两种给法」为最终的 (provider, baseUrl)，并过 {@link PlatformProviderPolicy}。
     *
     * <p>给了 {@code credentialId} → 以该凭据的 provider/baseUrl 为准（凭据是地址与密钥的真相源，
     * 运行时也是 {@code COALESCE(credential.base_url, config.base_url)}），请求体里的同名字段被忽略；
     * 凭据不存在或已停用 → 400（不是 404：这是请求体字段无效，不是路径资源缺失）。
     *
     * <p>未给 {@code credentialId} → 回落 provider+baseUrl 自填（既有调用方与 IT 走这条），
     * 仓储层再按 (provider, baseUrl) 反查/隐式建凭据。两者都缺 → 400。
     *
     * <p>返回 {@code Mono} 是必须的：凭据查找是 DB 读，不能在装配期同步做。
     */
    private Mono<Destination> resolveDestination(java.util.UUID credentialId, String provider, String baseUrl) {
        if (credentialId != null) {
            return credentials.findEnabledById(credentialId)
                    .switchIfEmpty(Mono.error(new IntelligenceException(400, "凭据不存在或已停用")))
                    .map(credential -> {
                        providerPolicy.validate(credential.provider(), credential.baseUrl());
                        return new Destination(credential.provider(), credential.baseUrl());
                    });
        }
        if (provider == null || provider.isBlank() || baseUrl == null || baseUrl.isBlank()) {
            return Mono.error(new IntelligenceException(400, "必须提供 credentialId，或同时提供 provider 与 baseUrl"));
        }
        return Mono.fromCallable(() -> {
            providerPolicy.validate(provider, baseUrl);
            return new Destination(provider, baseUrl);
        });
    }

    /**
     * 任务书 #58 S2.3：origin 不在受信端点表 → 422 + 引导文案（治理台 UX 闭环——先加端点再配模型）。
     * 运行时校验路径（TextCompletionClient 等）仍把它当普通 IllegalArgumentException。
     */
    private static IntelligenceException untrustedOrigin(UntrustedPlatformOriginException error) {
        return new IntelligenceException(422,
                "base URL 的端点不在受信列表，请先在受信端点中添加 " + error.origin());
    }

    /**
     * 任务书 #58 S2.3（原 AiCapabilityProviderConfigValidator 跨能力规则并入）：voice 与 retrieval
     * 的真实模型名不得相同——价目表按模型名唯一索引，重名会让计量归属歧义。保存前查对方 capability
     * 的当前生效行；Sandbox 行（本地假 provider）不参与。
     */
    private static final java.util.Map<String, String> CROSS_CAPABILITY_PAIR = java.util.Map.of(
            "voice", "retrieval", "retrieval", "voice");

    private Mono<Void> requireUniqueCrossCapabilityModel(PlatformModelConfig config) {
        String other = CROSS_CAPABILITY_PAIR.get(config.capability());
        if (other == null || "sandbox".equalsIgnoreCase(config.provider())) {
            return Mono.empty();
        }
        return repository.findCurrentByCapability(other)
                .filter(existing -> !"sandbox".equalsIgnoreCase(existing.provider()))
                .any(existing -> existing.model() != null && existing.model().trim()
                        .equalsIgnoreCase(config.model() == null ? "" : config.model().trim()))
                .flatMap(clash -> clash
                        ? Mono.error(new IntelligenceException(422,
                                "该模型名已被 " + other + " 能力使用——价目表按模型名唯一，语音与检索模型不得同名"))
                        : Mono.empty());
    }

    /** 归一后的目标地址（provider + baseUrl 已过受信白名单校验）。 */
    private record Destination(String provider, String baseUrl) {
    }

    /**
     * 列平台模型配置。默认只回生效行（{@code includeDisabled=false}，与既有契约逐字节兼容）。
     *
     * <p>{@code includeDisabled=true} 时含已停用的历史版本，供治理台「显示已停用」开关——
     * 停用是 {@code enabled=false} 的软删，行一直在库里，此前没有任何入口能看到或恢复它们。
     */
    @GetMapping
    public Flux<PlatformModelConfigResponse> list(
            @RequestParam(name = "includeDisabled", defaultValue = "false") boolean includeDisabled,
            ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMapMany(c -> (includeDisabled
                        ? repository.findAllIncludingDisabled()
                        : repository.findAllCurrent())
                        .map(PlatformModelConfigResponse::from));
    }

    /**
     * 恢复一行已停用配置。路径用 {@code id} 而非 (capability, modelRole)——同一组合下可能有多个
     * 历史版本，只有 id 能唯一指定要恢复哪一个。
     *
     * <p>该组合已有生效行 → 409：部分唯一索引会拒绝这次 UPDATE，此时静默顶掉线上配置远比报错危险，
     * 故让 admin 显式先停用现有行。已是生效状态或 id 不存在 → 404。
     */
    @PostMapping("/{id}/restore")
    public Mono<ResponseEntity<PlatformModelConfigResponse>> restore(
            @PathVariable UUID id, ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(admin -> transactions.transactional(repository.restore(id, admin.accountId()))
                        .map(saved -> ResponseEntity.ok(PlatformModelConfigResponse.from(saved)))
                        .switchIfEmpty(Mono.<ResponseEntity<PlatformModelConfigResponse>>error(
                                new IntelligenceException(404, "未找到已停用的平台模型配置: " + id))))
                .onErrorMap(DataIntegrityViolationException.class, e -> new IntelligenceException(409,
                        "该能力+角色已有生效配置，请先停用它再恢复此版本"));
    }

    /**
     * 硬删一行**已停用**配置。生效中的配置返回 409，必须先停用（两步确认，防误删）。
     *
     * <p>可以硬删是因为没有下游按外键引用它：{@code platform_model_config_history} 按值存快照且无 FK，
     * {@code ai_run.platform_model_version} 是冻结的 int。并发槽位在同一事务内先删（ON DELETE RESTRICT）。
     * 删除后该版本在配置表里不再可见，审计仍可从 history 复现。
     */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deleteById(
            @PathVariable UUID id, ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(admin -> repository.findById(id)
                        .switchIfEmpty(Mono.error(new IntelligenceException(404,
                                "未找到平台模型配置: " + id)))
                        .flatMap(config -> config.enabled()
                                ? Mono.<Boolean>error(new IntelligenceException(409,
                                        "生效中的配置不可删除，请先停用"))
                                : transactions.transactional(repository.deleteDisabled(id)))
                        .flatMap(deleted -> deleted
                                ? Mono.just(ResponseEntity.noContent().<Void>build())
                                : Mono.error(new IntelligenceException(404,
                                        "未找到已停用的平台模型配置: " + id))));
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
                        // defer：冲突分支不该付出凭据查询与策略校验的代价（switchIfEmpty 的备选在装配期即求值）
                        .switchIfEmpty(Mono.defer(() -> buildForCreate(body)))
                        .flatMap(cfg -> requireUniqueCrossCapabilityModel(cfg)
                                .then(transactions.transactional(repository.create(cfg, admin.accountId())
                                        .then(repository.findCurrent(body.capability(), body.modelRole())))))
                        .map(saved -> ResponseEntity.status(201).body(PlatformModelConfigResponse.from(saved))))
                .onErrorResume(DataIntegrityViolationException.class,
                        e -> Mono.error(new IntelligenceException(409, "该能力+角色已有平台模型配置")))
                .onErrorMap(UntrustedPlatformOriginException.class,
                        PlatformModelConfigController::untrustedOrigin);
    }

    @PutMapping("/{capability}/{modelRole}")
    public Mono<ResponseEntity<PlatformModelConfigResponse>> revise(
            @PathVariable String capability,
            @PathVariable String modelRole,
            @Valid @RequestBody UpdatePlatformModelRequest body,
            ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(admin -> buildForUpdate(capability, modelRole, body)
                        .flatMap(next -> requireUniqueCrossCapabilityModel(next)
                                .then(transactions.transactional(
                                        repository.revise(capability, modelRole, next, admin.accountId())))
                                .map(saved -> ResponseEntity.ok(PlatformModelConfigResponse.from(saved)))
                                .switchIfEmpty(Mono.error(notFound(capability, modelRole)))))
                .onErrorMap(UntrustedPlatformOriginException.class,
                        PlatformModelConfigController::untrustedOrigin);
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

    private Mono<PlatformModelConfig> buildForCreate(CreatePlatformModelRequest body) {
        return resolveDestination(body.credentialId(), body.provider(), body.baseUrl())
                .map(destination -> new PlatformModelConfig(null, body.capability(), body.modelRole(),
                        destination.provider(), body.model(), destination.baseUrl(), body.maxConcurrency(),
                        healthOrDefault(body.healthStatus()), true, 1, null, null, null));
    }

    private Mono<PlatformModelConfig> buildForUpdate(
            String capability, String modelRole, UpdatePlatformModelRequest body) {
        return resolveDestination(body.credentialId(), body.provider(), body.baseUrl())
                .map(destination -> new PlatformModelConfig(null, capability, modelRole, destination.provider(),
                        body.model(), destination.baseUrl(), body.maxConcurrency(),
                        healthOrDefault(body.healthStatus()), true, 1, null, null, null));
    }

    private static String healthOrDefault(String healthStatus) {
        return healthStatus != null ? healthStatus : PlatformModelConfig.HEALTH_HEALTHY;
    }

    private static IntelligenceException notFound(String capability, String modelRole) {
        return new IntelligenceException(404, "未找到平台模型配置: " + capability + "/" + modelRole);
    }
}
