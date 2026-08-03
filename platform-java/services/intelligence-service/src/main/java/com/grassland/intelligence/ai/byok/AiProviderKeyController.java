package com.grassland.intelligence.ai.byok;

import com.grassland.crypto.CryptoKekConfiguredCondition;
import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.crypto.MaskedKey;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
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

/**
 * AI Provider BYOK 密钥管理 API（GL-P3-AI-001 Phase 1）。
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

    public AiProviderKeyController(
            IntelligenceCallerResolver callers,
            AiProviderKeyRepository repository,
            EnvelopeEncryption encryption) {
        this.callers = callers;
        this.repository = repository;
        this.encryption = encryption;
    }

    /**
     * POST /api/ai/keys - 创建 BYOK 密钥。
     *
     * <p>鉴权：需要登录。个人用户只能创建个人密钥；组织成员可创建组织密钥。
     */
    @PostMapping
    public Mono<ResponseEntity<AiProviderKeyResponse>> create(
            @Valid @RequestBody CreateAiProviderKeyRequest body,
            ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> {
                    // 加密明文密钥
                    String encryptedKey = encryption.encrypt(body.apiKey());
                    String maskedHint = MaskedKey.mask(body.apiKey());

                    AiProviderKey key = AiProviderKey.forCreate(
                            caller.organizationId(),  // 个人用户为 null
                            caller.accountId(),
                            body.capability(),
                            body.provider(),
                            body.baseUrl(),
                            body.model(),
                            encryptedKey,
                            "v1",  // 首期固定 v1
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
                });
    }

    /**
     * GET /api/ai/keys - 列出当前用户的所有密钥（个人 + 组织）。
     */
    @GetMapping
    public Flux<AiProviderKeyResponse> list(ServerWebExchange exchange) {
        // WebFlux 将 Flux 汇聚为 JSON 数组（与 legacy {success,data:[...]} 的信封差异由路由开关默认 false 兜底）
        return callers.resolve(exchange.getRequest())
                .flatMapMany(caller -> repository.findByOwner(caller.accountId())
                        .map(AiProviderKey::toResponse));
    }

    /**
     * GET /api/ai/keys/{id} - 获取密钥详情。
     *
     * <p>鉴权：用户只能查看自己拥有的密钥（个人密钥或所属组织密钥）。
     */
    @GetMapping("/{id}")
    public Mono<ResponseEntity<AiProviderKeyResponse>> getById(
            @PathVariable UUID id,
            ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> repository.findById(id)
                        .flatMap(key -> canManage(key.id(), caller.accountId(), caller.organizationId())
                                .flatMap(canManage -> {
                                    if (!canManage) {
                                        return Mono.error(new IntelligenceException(403, "无权访问此密钥"));
                                    }
                                    return Mono.just(ResponseEntity.ok(key.toResponse()));
                                })))
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
                .flatMap(caller -> repository.findById(id)
                        .flatMap(key -> canManage(key.id(), caller.accountId(), caller.organizationId())
                                .flatMap(canManage -> {
                                    if (!canManage) {
                                        return Mono.error(new IntelligenceException(403, "无权修改此密钥"));
                                    }
                                    return repository.updateConfig(id, body.baseUrl(), body.model())
                                            .flatMap(updated -> {
                                                if (!updated) {
                                                    return Mono.error(new IntelligenceException(404, "密钥不存在"));
                                                }
                                                return repository.findById(id);  // 重新获取更新后的记录
                                            })
                                            .map(k -> ResponseEntity.ok(k.toResponse()));
                                })))
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
                .flatMap(caller -> repository.findById(id)
                        .flatMap(key -> canManage(key.id(), caller.accountId(), caller.organizationId())
                                .flatMap(canManage -> {
                                    if (!canManage) {
                                        return Mono.error(new IntelligenceException(403, "无权修改此密钥"));
                                    }
                                    // 加密新密钥
                                    String encryptedKey = encryption.encrypt(body.apiKey());
                                    String maskedHint = MaskedKey.mask(body.apiKey());
                                    String newKeyVersion = encryption.rotateKey();

                                    return repository.updateKey(id, encryptedKey, newKeyVersion, maskedHint)
                                            .flatMap(updated -> {
                                                if (!updated) {
                                                    return Mono.error(new IntelligenceException(404, "密钥不存在"));
                                                }
                                                return repository.findById(id);  // 重新获取更新后的记录
                                            })
                                            .map(k -> ResponseEntity.ok(k.toResponse()));
                                })))
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
                .flatMap(caller -> repository.findById(id)
                        .flatMap(key -> canManage(key.id(), caller.accountId(), caller.organizationId())
                                .flatMap(canManage -> {
                                    if (!canManage) {
                                        return Mono.error(new IntelligenceException(403, "无权删除此密钥"));
                                    }
                                    return repository.delete(id)
                                            .map(deleted -> {
                                                if (!deleted) {
                                                    throw new IntelligenceException(404, "密钥不存在");
                                                }
                                                return ResponseEntity.noContent().<Void>build();
                                            });
                                })))
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "密钥不存在")));
    }

    /** V5 唯一索引（org/personal + owner + capability + provider，enabled）冲突 → 409。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DataIntegrityViolationException error) {
        return ResponseEntity.status(409)
                .body(Map.of("success", false, "error", "该能力下已存在有效的密钥，请先删除旧密钥或轮换"));
    }

    /** 检查用户是否可以管理指定密钥。 */
    private Mono<Boolean> canManage(UUID id, String accountId, String userOrgId) {
        // 这里简化处理：如果用户是密钥的创建者，或者密钥属于用户的组织
        // 实际的组织成员检查需要跨服务调用 identity，这里暂时只检查 ownerAccountId
        return repository.isOwner(id, accountId)
                .map(isOwner -> {
                    if (isOwner) {
                        return true;
                    }
                    // TODO: 如果密钥有 organizationId，需要检查用户是否是该组织的成员
                    // 这需要跨服务调用 identity 的组织成员 API
                    return false;
                });
    }
}
