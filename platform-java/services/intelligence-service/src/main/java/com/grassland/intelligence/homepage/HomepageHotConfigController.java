package com.grassland.intelligence.homepage;

import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.crypto.MaskedKey;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 首页热点平台配置 admin API（任务书 #47 S7b / D18①，V50 地建的读取/写入面）。
 *
 * <p>端点（{@code requireAdmin}——平台后台角色）：
 * <ul>
 * <li>GET /api/admin/homepage/hot-config — 当前配置（token 只回掩码）</li>
 * <li>PUT /api/admin/homepage/hot-config — 修订（{@code expectedVersion} 乐观锁，冲突 409）。
 * {@code alapiToken} 不传 = 保持不变；传空格 = 清空；传新值 = 信封加密后替换
 * （KEK 未配 → 503，绝不退化存明文，同平台凭据口径）。</li>
 * </ul>
 */
@RestController
public class HomepageHotConfigController {

    private final IntelligenceCallerResolver callers;
    private final HomepageHotConfigRepository repository;
    private final ObjectProvider<EnvelopeEncryption> encryptionProvider;

    public HomepageHotConfigController(IntelligenceCallerResolver callers,
            HomepageHotConfigRepository repository, ObjectProvider<EnvelopeEncryption> encryptionProvider) {
        this.callers = callers;
        this.repository = repository;
        this.encryptionProvider = encryptionProvider;
    }

    @GetMapping("/api/admin/homepage/hot-config")
    public Mono<Map<String, Object>> get(ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(caller -> repository.findOrDefault())
                .map(config -> Map.of("success", true, "data", body(config)));
    }

    @PutMapping("/api/admin/homepage/hot-config")
    public Mono<Map<String, Object>> update(@RequestBody UpdateRequest body, ServerWebExchange exchange) {
        if (!HomepageHotConfig.PROVIDER_60S.equals(body.provider())
                && !HomepageHotConfig.PROVIDER_ALAPI.equals(body.provider())) {
            throw new IntelligenceException(400, "provider 仅支持 60s / alapi");
        }
        if (body.expectedVersion() == null || body.expectedVersion() < 0) {
            throw new IntelligenceException(400, "expectedVersion 必填");
        }
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(caller -> applyUpdate(body, caller.accountId()))
                .map(config -> Map.of("success", true, "data", body(config)));
    }

    private Mono<HomepageHotConfig> applyUpdate(UpdateRequest body, String adminId) {
        if (body.alapiToken() == null) {
            // 不传 = 保持现有 token（COALESCE 语义在仓储 upsert 内）
            return upsert(body.provider(), null, null, null, body.expectedVersion(), adminId);
        }
        String token = body.alapiToken().trim();
        if (token.isEmpty()) {
            // 空格 = 清空：先清 token（不动 provider），行不存在时清空退化为按新 provider 建行
            return repository.clearAlapiToken(body.expectedVersion(), adminId)
                    .flatMap(cleared -> body.provider().equals(cleared.provider())
                            ? Mono.just(cleared)
                            : upsert(body.provider(), null, null, null, cleared.version(), adminId))
                    .switchIfEmpty(Mono.defer(() ->
                            upsert(body.provider(), null, null, null, body.expectedVersion(), adminId)));
        }
        if (!MaskedKey.isSafePrintable(token)) {
            throw new IntelligenceException(400, "ALAPI Token 含不可打印字符");
        }
        EnvelopeEncryption crypto = encryptionProvider.getIfAvailable();
        if (crypto == null) {
            throw new IntelligenceException(503, "ALAPI Token 加密不可用：未配置 CRYPTO_KEK_BASE64");
        }
        return Mono.fromCallable(() -> crypto.encrypt(token))
                .flatMap(encrypted -> upsert(body.provider(), encrypted, crypto.keyVersion(encrypted),
                        MaskedKey.mask(token), body.expectedVersion(), adminId));
    }

    private Mono<HomepageHotConfig> upsert(String provider, String encrypted, String keyVersion, String masked,
            long expectedVersion, String adminId) {
        return repository.upsert(provider, encrypted, keyVersion, masked, expectedVersion, adminId)
                .switchIfEmpty(Mono.error(new IntelligenceException(409, "配置已被其他管理员修改，请刷新后重试")));
    }

    private static Map<String, Object> body(HomepageHotConfig config) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("provider", config.provider());
        data.put("alapiTokenMasked", config.alapiTokenMasked());
        data.put("hasAlapiToken", config.hasAlapiToken());
        data.put("version", config.version());
        data.put("updatedBy", config.updatedBy());
        data.put("updatedAt", config.updatedAt() == null ? null : config.updatedAt().toString());
        return data;
    }

    /** PUT 请求体。{@code alapiToken}：null=保持；空白=清空；其余=新值。 */
    public record UpdateRequest(String provider, String alapiToken, Long expectedVersion) {
    }

    @ExceptionHandler(IntelligenceException.class)
    public ResponseEntity<Map<String, Object>> handleError(IntelligenceException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }
}
