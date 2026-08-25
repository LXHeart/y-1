package com.grassland.intelligence.ai.controlplane;

import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.crypto.MaskedKey;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
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
 * 平台通用凭据 admin API（任务书 #47 S1；D1–D6）。
 *
 * <p>端点（全部 {@code requireAdmin}，与 {@code /api/admin/ai/models} 同闸——D4 刻意不新增 PLATFORM_AI 角色）：
 * <ul>
 *   <li>GET    /api/admin/ai/credentials — 列出有效凭据（只回掩码）</li>
 *   <li>GET    /api/admin/ai/credentials/{id} — 详情</li>
 *   <li>POST   /api/admin/ai/credentials — 创建；同 (provider, baseUrl) 已有 → 409</li>
 *   <li>PUT    /api/admin/ai/credentials/{id} — 改连接信息（不含密钥）→ version+1</li>
 *   <li>PUT    /api/admin/ai/credentials/{id}/key — 轮换密钥 → version+1</li>
 *   <li>DELETE /api/admin/ai/credentials/{id} — 软删；仍被有效模型配置引用 → 409（D6）</li>
 * </ul>
 *
 * <p><b>KEK 门控为 503 而非 404</b>：与 {@code AiProviderKeyController} 的
 * {@code @Conditional(CryptoKekConfiguredCondition)}（整体不注册→404）刻意不同。这是运营端点，
 * 404 会让 admin 以为「功能不存在」，而真实原因是加密基建未配；503 + 明确文案才可诊断（D8/验收 2）。
 * 无论如何都不退化存明文。读端点与无密钥凭据不需要 KEK，故不挡。
 */
@RestController
@RequestMapping("/api/admin/ai/credentials")
public class PlatformProviderCredentialController {

    private final IntelligenceCallerResolver callers;
    private final PlatformProviderCredentialRepository repository;
    private final PlatformProviderPolicy providerPolicy;
    private final TransactionalOperator transactions;
    /** KEK 未配时 bean 不存在（CryptoAutoConfiguration:27 的 @Conditional）——用 ObjectProvider 才能转 503。 */
    private final ObjectProvider<EnvelopeEncryption> encryptionProvider;

    public PlatformProviderCredentialController(
            IntelligenceCallerResolver callers,
            PlatformProviderCredentialRepository repository,
            PlatformProviderPolicy providerPolicy,
            TransactionalOperator transactions,
            ObjectProvider<EnvelopeEncryption> encryptionProvider) {
        this.callers = callers;
        this.repository = repository;
        this.providerPolicy = providerPolicy;
        this.transactions = transactions;
        this.encryptionProvider = encryptionProvider;
    }

    @GetMapping
    public Flux<PlatformProviderCredentialResponse> list(ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMapMany(admin -> repository.findAllEnabled()
                        .map(PlatformProviderCredentialResponse::from));
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<PlatformProviderCredentialResponse>> get(
            @PathVariable UUID id, ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(admin -> repository.findEnabledById(id)
                        .map(c -> ResponseEntity.ok(PlatformProviderCredentialResponse.from(c)))
                        .switchIfEmpty(Mono.error(notFound(id))));
    }

    @PostMapping
    public Mono<ResponseEntity<PlatformProviderCredentialResponse>> create(
            @Valid @RequestBody CreatePlatformCredentialRequest body, ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(admin -> {
                    // baseUrl 仍过受信目的地校验（IllegalArgumentException → 400）
                    providerPolicy.validate(body.provider(), body.baseUrl());
                    boolean withKey = body.apiKey() != null && !body.apiKey().isBlank();
                    // sandbox 等无密钥凭据不需要 KEK；有密钥才要求加密基建就位
                    EnvelopeEncryption encryption = withKey ? requireEncryption() : null;
                    String encryptedKey = withKey ? encryption.encrypt(body.apiKey()) : null;
                    String keyVersion = withKey ? encryption.keyVersion(encryptedKey) : null;
                    String maskedHint = withKey ? MaskedKey.mask(body.apiKey()) : null;

                    return transactions.transactional(
                                    repository.create(body.name(), body.provider(), body.baseUrl(),
                                                    encryptedKey, keyVersion, maskedHint, admin.accountId())
                                            .flatMap(repository::findEnabledById))
                            .map(saved -> ResponseEntity.status(201)
                                    .body(PlatformProviderCredentialResponse.from(saved)));
                })
                .onErrorResume(DataIntegrityViolationException.class, error -> Mono.error(
                        new IntelligenceException(409, "该 provider + baseUrl 已有有效凭据，或标签重复")));
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<PlatformProviderCredentialResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePlatformCredentialRequest body,
            ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(admin -> {
                    providerPolicy.validate(body.provider(), body.baseUrl());
                    return transactions.transactional(
                                    repository.updateConnection(id, body.name(), body.provider(),
                                                    body.baseUrl(), admin.accountId())
                                            .flatMap(updated -> updated
                                                    ? repository.findEnabledById(id)
                                                    : Mono.empty()))
                            .map(saved -> ResponseEntity.ok(PlatformProviderCredentialResponse.from(saved)))
                            .switchIfEmpty(Mono.error(notFound(id)));
                })
                .onErrorResume(DataIntegrityViolationException.class, error -> Mono.error(
                        new IntelligenceException(409, "该 provider + baseUrl 已有有效凭据，或标签重复")));
    }

    @PutMapping("/{id}/key")
    public Mono<ResponseEntity<PlatformProviderCredentialResponse>> rotateKey(
            @PathVariable UUID id,
            @Valid @RequestBody RotatePlatformCredentialRequest body,
            ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(admin -> {
                    EnvelopeEncryption encryption = requireEncryption();
                    String encryptedKey = encryption.encrypt(body.apiKey());
                    String keyVersion = encryption.keyVersion(encryptedKey);
                    String maskedHint = MaskedKey.mask(body.apiKey());

                    return transactions.transactional(
                                    repository.rotateKey(id, encryptedKey, keyVersion, maskedHint,
                                                    admin.accountId())
                                            .flatMap(rotated -> rotated
                                                    ? repository.findEnabledById(id)
                                                    : Mono.empty()))
                            .map(saved -> ResponseEntity.ok(PlatformProviderCredentialResponse.from(saved)))
                            .switchIfEmpty(Mono.error(notFound(id)));
                });
    }

    /** 软删；仍被有效模型配置引用时拒绝并报引用数（D6：让代价在点击那一刻显示，而非运行时 503）。 */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> disable(@PathVariable UUID id, ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(admin -> repository.findEnabledById(id)
                        .switchIfEmpty(Mono.error(notFound(id)))
                        .flatMap(existing -> repository.countEnabledReferences(id))
                        .flatMap(references -> references > 0
                                ? Mono.<ResponseEntity<Void>>error(new IntelligenceException(409,
                                        "该凭据仍被 " + references + " 个模型配置引用，请先改指向后再停用"))
                                : transactions.transactional(repository.disable(id, admin.accountId()))
                                        .flatMap(done -> done
                                                ? Mono.just(ResponseEntity.noContent().<Void>build())
                                                : Mono.error(notFound(id)))));
    }

    private EnvelopeEncryption requireEncryption() {
        EnvelopeEncryption encryption = encryptionProvider.getIfAvailable();
        if (encryption == null) {
            throw new IntelligenceException(503, "加密基建未配置（CRYPTO_KEK_BASE64），无法保存平台凭据");
        }
        return encryption;
    }

    private static IntelligenceException notFound(UUID id) {
        return new IntelligenceException(404, "未找到平台凭据: " + id);
    }
}
