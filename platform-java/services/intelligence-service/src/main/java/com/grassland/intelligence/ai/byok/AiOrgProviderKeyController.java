package com.grassland.intelligence.ai.byok;

import com.grassland.crypto.CryptoKekConfiguredCondition;
import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.crypto.MaskedKey;
import com.grassland.intelligence.security.IdentityOrgAuthorizationClient;
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
 * 组织级 BYOK 密钥管理 API（ADR-D17）。
 *
 * <p>组织 admin/owner 维护组织密钥（经 identity 权威校验）；普通成员「可用不可见」
 * （D-11/HLD §12.3）——成员不提供任何组织密钥读取端点，仅运行时路由兜底使用。
 *
 * <p>端点（均要求组织 admin/owner）：
 * <ul>
 *   <li>POST /api/ai/organizations/{orgId}/keys - 创建组织密钥</li>
 *   <li>GET /api/ai/organizations/{orgId}/keys - 列出组织密钥</li>
 *   <li>GET /api/ai/organizations/{orgId}/keys/{id} - 密钥详情</li>
 *   <li>PUT /api/ai/organizations/{orgId}/keys/{id} - 更新配置</li>
 *   <li>PUT /api/ai/organizations/{orgId}/keys/{id}/key - 轮换密钥</li>
 *   <li>DELETE /api/ai/organizations/{orgId}/keys/{id} - 停用（软删）</li>
 * </ul>
 *
 * <p>fail-closed 门控与个人版同款：KEK 未配置时本 controller 整体不注册（404）。
 * 组织不存在与非管理员统一 404「组织不存在」，隐藏组织存在性（同 {@code AiOrgBudgetController}）。
 */
@RestController
@RequestMapping("/api/ai/organizations/{organizationId}/keys")
@Conditional(CryptoKekConfiguredCondition.class)
public class AiOrgProviderKeyController {

    private final IntelligenceCallerResolver callers;
    private final IdentityOrgAuthorizationClient orgAuthorization;
    private final AiProviderKeyRepository repository;
    private final EnvelopeEncryption encryption;
    private final DnsPinningResolver dnsPinning;

    public AiOrgProviderKeyController(
            IntelligenceCallerResolver callers,
            IdentityOrgAuthorizationClient orgAuthorization,
            AiProviderKeyRepository repository,
            EnvelopeEncryption encryption,
            DnsPinningResolver dnsPinning) {
        this.callers = callers;
        this.orgAuthorization = orgAuthorization;
        this.repository = repository;
        this.encryption = encryption;
        this.dnsPinning = dnsPinning;
    }

    @PostMapping
    public Mono<ResponseEntity<AiProviderKeyResponse>> create(
            @PathVariable String organizationId,
            @Valid @RequestBody CreateAiProviderKeyRequest body,
            ServerWebExchange exchange) {
        return callers.requireUser(exchange.getRequest())
                .flatMap(caller -> orgAuthorization
                        .require(caller.accountId(), organizationId, "admin")
                        .onErrorMap(IntelligenceException.class, error ->
                                error.status() == 403 || error.status() == 404
                                        ? new IntelligenceException(404, "组织不存在") : error)
                        .then(validateForStorage(body.baseUrl()))
                        .then(Mono.defer(() -> {
                            String encryptedKey = encryption.encrypt(body.apiKey());
                            String maskedHint = MaskedKey.mask(body.apiKey());
                            AiProviderKey key = AiProviderKey.forCreate(
                                    organizationId,
                                    caller.accountId(),
                                    body.capability(),
                                    body.provider(),
                                    body.baseUrl(),
                                    body.model(),
                                    encryptedKey,
                                    encryption.keyVersion(encryptedKey),
                                    maskedHint);
                            return repository.create(key)
                                    .map(id -> new AiProviderKey(
                                            id, key.organizationId(), key.ownerAccountId(), key.capability(),
                                            key.provider(), key.baseUrl(), key.model(), key.encryptedKey(),
                                            key.keyVersion(), key.maskedHint(), key.enabled(),
                                            key.createdAt(), key.updatedAt()))
                                    .map(AiProviderKey::toResponse)
                                    .map(resp -> ResponseEntity.status(201).body(resp));
                        })));
    }

    @GetMapping
    public Flux<AiProviderKeyResponse> list(
            @PathVariable String organizationId, ServerWebExchange exchange) {
        // WebFlux 将 Flux 汇聚为 JSON 数组；该形状是当前公开契约。
        return requireAdmin(organizationId, exchange)
                .thenMany(repository.findOrgByOrganization(organizationId).map(AiProviderKey::toResponse));
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<AiProviderKeyResponse>> getById(
            @PathVariable String organizationId,
            @PathVariable UUID id,
            ServerWebExchange exchange) {
        return requireAdmin(organizationId, exchange)
                .then(repository.findOrgByIdAndOrganization(id, organizationId)
                        .map(key -> ResponseEntity.ok(key.toResponse())))
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "密钥不存在")));
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<AiProviderKeyResponse>> update(
            @PathVariable String organizationId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAiProviderKeyRequest body,
            ServerWebExchange exchange) {
        return requireAdmin(organizationId, exchange)
                .then(repository.findOrgByIdAndOrganization(id, organizationId)
                        .flatMap(ignored -> validateForStorage(body.baseUrl()))
                        .then(repository.updateOrgConfig(id, organizationId, body.baseUrl(), body.model())
                                .flatMap(updated -> updated
                                        ? repository.findOrgByIdAndOrganization(id, organizationId)
                                        : Mono.empty())
                                .map(key -> ResponseEntity.ok(key.toResponse()))))
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "密钥不存在")));
    }

    @PutMapping("/{id}/key")
    public Mono<ResponseEntity<AiProviderKeyResponse>> rotateKey(
            @PathVariable String organizationId,
            @PathVariable UUID id,
            @Valid @RequestBody RotateAiProviderKeyRequest body,
            ServerWebExchange exchange) {
        return requireAdmin(organizationId, exchange)
                .then(repository.findOrgByIdAndOrganization(id, organizationId)
                        .flatMap(ignored -> {
                            // 同 AiProviderKeyController：组织换自己的 API key 不该动平台 KEK 版本号
                            String encryptedKey = encryption.encrypt(body.apiKey());
                            String maskedHint = MaskedKey.mask(body.apiKey());
                            String newKeyVersion = encryption.keyVersion(encryptedKey);
                            return repository.updateOrgKey(id, organizationId, encryptedKey, newKeyVersion,
                                            maskedHint)
                                    .flatMap(updated -> updated
                                            ? repository.findOrgByIdAndOrganization(id, organizationId)
                                            : Mono.empty())
                                    .map(key -> ResponseEntity.ok(key.toResponse()));
                        }))
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "密钥不存在")));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(
            @PathVariable String organizationId,
            @PathVariable UUID id,
            ServerWebExchange exchange) {
        return requireAdmin(organizationId, exchange)
                .then(repository.findOrgByIdAndOrganization(id, organizationId)
                        .flatMap(ignored -> repository.deleteOrg(id, organizationId)
                                .flatMap(deleted -> deleted
                                        ? Mono.just(ResponseEntity.noContent().<Void>build())
                                        : Mono.<ResponseEntity<Void>>empty())))
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "密钥不存在")));
    }

    /** V41 组织维唯一索引（org + capability，enabled）冲突 -> 409。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DataIntegrityViolationException error) {
        return ResponseEntity.status(409)
                .body(Map.of("success", false, "error", "该能力下组织已存在有效密钥，请先停用旧密钥或轮换"));
    }

    private Mono<Void> requireAdmin(String organizationId, ServerWebExchange exchange) {
        return callers.requireUser(exchange.getRequest())
                .flatMap(caller -> orgAuthorization.require(caller.accountId(), organizationId, "admin")
                        .onErrorMap(IntelligenceException.class, error ->
                                error.status() == 403 || error.status() == 404
                                        ? new IntelligenceException(404, "组织不存在") : error));
    }

    private Mono<Void> validateForStorage(String baseUrl) {
        return Mono.fromCallable(() -> ProviderUrlGuard.validateByokForStorage(baseUrl, dnsPinning))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
