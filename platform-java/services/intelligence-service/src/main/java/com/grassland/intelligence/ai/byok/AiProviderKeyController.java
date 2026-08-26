package com.grassland.intelligence.ai.byok;

import com.grassland.crypto.CryptoKekConfiguredCondition;
import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.crypto.MaskedKey;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.ai.DnsPinningResolver;
import com.grassland.intelligence.ai.ProviderUrlGuard;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Conditional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
import reactor.core.scheduler.Schedulers;

/**
 * AI Provider BYOK 密钥管理 API（GL-P3-AI-001 Phase 1）。个人作用域；组织级 BYOK 由
 * {@link AiOrgProviderKeyController} 承载（ADR-D17，组织 admin 门禁）。
 *
 * <p>端点：
 * <ul>
 *   <li>POST /api/ai/keys - 创建 BYOK 密钥</li>
 *   <li>GET /api/ai/keys - 列出当前用户的密钥</li>
 *   <li>GET /api/ai/keys/{id} - 获取密钥详情</li>
 *   <li>PUT /api/ai/keys/{id} - 更新配置</li>
 *   <li>PUT /api/ai/keys/{id}/key - 更换密钥（轮换）</li>
 *   <li>DELETE /api/ai/keys/{id} - 删除密钥（软删）</li>
 * </ul>
 *
 * <p>fail-closed 门控：KEK（{@code crypto.kek.encoded} / {@code CRYPTO_KEK_BASE64}）未配置时
 * {@link EnvelopeEncryption} 不装配，本 controller 整体不注册（端点 404），与视频生成能力
 * gate（GL-P0-BILL-001）同口径——加密基建不可用时不开放 BYOK 入口。
 */
@RestController
@RequestMapping("/api/ai/keys")
@Conditional(CryptoKekConfiguredCondition.class)
public class AiProviderKeyController {

    private final IntelligenceCallerResolver callers;
    private final AiProviderKeyRepository repository;
    private final EnvelopeEncryption encryption;
    private final DnsPinningResolver dnsPinning;

    public AiProviderKeyController(
            IntelligenceCallerResolver callers,
            AiProviderKeyRepository repository,
            EnvelopeEncryption encryption,
            DnsPinningResolver dnsPinning) {
        this.callers = callers;
        this.repository = repository;
        this.encryption = encryption;
        this.dnsPinning = dnsPinning;
    }

    /**
     * POST /api/ai/keys - 创建 BYOK 密钥。
     *
     * <p>鉴权：需要登录。密钥始终归当前账号所有，即使调用者断言带组织上下文也不创建组织密钥。
     */
    @PostMapping
    public Mono<ResponseEntity<AiProviderKeyResponse>> create(
            @Valid @RequestBody CreateAiProviderKeyRequest body,
            ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> validateForStorage(body.baseUrl()).then(Mono.defer(() -> {
                    // 加密明文密钥
                    String encryptedKey = encryption.encrypt(body.apiKey());
                    String maskedHint = MaskedKey.mask(body.apiKey());

                    AiProviderKey key = AiProviderKey.forCreate(
                            null,  // 组织密钥必须走 AiOrgProviderKeyController（admin 门禁），此处强制个人作用域
                            caller.accountId(),
                            body.capability(),
                            body.provider(),
                            body.baseUrl(),
                            body.model(),
                            encryptedKey,
                            encryption.keyVersion(encryptedKey),
                            maskedHint
                    );

                    return repository.create(key)
                            .map(id -> new AiProviderKey(
                                    id,
                                    key.organizationId(),
                                    key.ownerAccountId(),
                                    key.capability(),
                                    key.provider(),
                                    key.baseUrl(),
                                    key.model(),
                                    key.encryptedKey(),
                                    key.keyVersion(),
                                    key.maskedHint(),
                                    key.enabled(),
                                    key.createdAt(),
                                    key.updatedAt()
                            ))
                            .map(AiProviderKey::toResponse)
                            .map(resp -> ResponseEntity.status(201).body(resp));
                })));
    }

    /**
     * GET /api/ai/keys - 列出当前用户的个人密钥。
     */
    @GetMapping
    public Flux<AiProviderKeyResponse> list(ServerWebExchange exchange) {
        // WebFlux 将 Flux 汇聚为 JSON 数组；该形状是当前公开契约。
        return callers.resolve(exchange.getRequest())
                .flatMapMany(caller -> repository.findPersonalByOwner(caller.accountId())
                        .map(AiProviderKey::toResponse));
    }

    /**
     * GET /api/ai/keys/{id} - 获取密钥详情。
     *
     * <p>鉴权：用户只能查看自己拥有的个人密钥。
     */
    @GetMapping("/{id}")
    public Mono<ResponseEntity<AiProviderKeyResponse>> getById(
            @PathVariable UUID id,
            ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> repository.findPersonalByIdAndOwner(id, caller.accountId())
                        .map(key -> ResponseEntity.ok(key.toResponse())))
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "密钥不存在")));
    }

    /**
     * PUT /api/ai/keys/{id} - 更新密钥配置（不含 apiKey）。
     *
     * <p>鉴权：只有密钥所有者可以更新。
     */
    @PutMapping("/{id}")
    public Mono<ResponseEntity<AiProviderKeyResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAiProviderKeyRequest body,
            ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> repository.findPersonalByIdAndOwner(id, caller.accountId())
                        .flatMap(key -> validateForStorage(body.baseUrl())
                                .then(repository.updatePersonalConfig(
                                            id, caller.accountId(), body.baseUrl(), body.model())
                                    .flatMap(updated -> updated
                                            ? repository.findPersonalByIdAndOwner(id, caller.accountId())
                                            : Mono.empty())
                                    .map(k -> ResponseEntity.ok(k.toResponse())))))
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "密钥不存在")));
    }

    /**
     * PUT /api/ai/keys/{id}/key - 更换密钥（密钥轮换）。
     *
     * <p>鉴权：只有密钥所有者可以轮换密钥。
     */
    @PutMapping("/{id}/key")
    public Mono<ResponseEntity<AiProviderKeyResponse>> rotateKey(
            @PathVariable UUID id,
            @Valid @RequestBody RotateAiProviderKeyRequest body,
            ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> repository.findPersonalByIdAndOwner(id, caller.accountId())
                        .flatMap(key -> {
                            // 不再调 encryption.rotateKey()：那是平台 KEK 的版本号，与「用户换自己的
                            // API key」无关。原实现每次用户轮换都把全局版本 +1 却不换 KEK 材料，
                            // 使密文首字节与密钥材料失去对应（且计数器重启归 1）。
                            String encryptedKey = encryption.encrypt(body.apiKey());
                            String maskedHint = MaskedKey.mask(body.apiKey());
                            String newKeyVersion = encryption.keyVersion(encryptedKey);

                            return repository.updatePersonalKey(
                                            id, caller.accountId(), encryptedKey, newKeyVersion, maskedHint)
                                    .flatMap(updated -> updated
                                            ? repository.findPersonalByIdAndOwner(id, caller.accountId())
                                            : Mono.empty())
                                    .map(k -> ResponseEntity.ok(k.toResponse()));
                        }))
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "密钥不存在")));
    }

    /**
     * DELETE /api/ai/keys/{id} - 删除密钥（软删，enabled=false）。
     *
     * <p>鉴权：只有密钥所有者可以删除。
     */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(
            @PathVariable UUID id,
            ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> repository.findPersonalByIdAndOwner(id, caller.accountId())
                        .flatMap(key -> repository.deletePersonal(id, caller.accountId())
                                .flatMap(deleted -> deleted
                                        ? Mono.just(ResponseEntity.noContent().<Void>build())
                                        : Mono.empty())))
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "密钥不存在")));
    }

    /** V13 唯一索引（个人 owner + capability，enabled）冲突 -> 409。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DataIntegrityViolationException error) {
        return ResponseEntity.status(409)
                .body(Map.of("success", false, "error", "该能力下已存在有效的密钥，请先删除旧密钥或轮换"));
    }

    private Mono<Void> validateForStorage(String baseUrl) {
        return Mono.fromCallable(() -> ProviderUrlGuard.validateByokForStorage(baseUrl, dnsPinning))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

}
