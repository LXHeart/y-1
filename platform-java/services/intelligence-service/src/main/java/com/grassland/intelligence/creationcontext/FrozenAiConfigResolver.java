package com.grassland.intelligence.creationcontext;

import com.grassland.intelligence.ai.byok.AiProviderKey;
import com.grassland.intelligence.ai.byok.AiProviderKeyRepository;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.ai.controlplane.PlatformModelConfigRepository;
import com.grassland.intelligence.security.IdentityOrgAuthorizationClient;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Resolves the exact AI configuration captured by a PRD 4.12 creation snapshot. */
@Service
public class FrozenAiConfigResolver {
    private static final String FROZEN_BYOK_CHANGED = "创作开始时冻结的 BYOK 配置已变化或不可用";

    private final CreationContextSnapshotRepository snapshots;
    private final AiProviderKeyRepository keys;
    private final PlatformModelConfigRepository platformModels;
    private final IdentityOrgAuthorizationClient orgAuthorization;

    public FrozenAiConfigResolver(CreationContextSnapshotRepository snapshots,
                                  AiProviderKeyRepository keys,
                                  PlatformModelConfigRepository platformModels,
                                  IdentityOrgAuthorizationClient orgAuthorization) {
        this.snapshots = snapshots;
        this.keys = keys;
        this.platformModels = platformModels;
        this.orgAuthorization = orgAuthorization;
    }

    public Mono<ResolvedSnapshot> resolve(UUID snapshotId, String accountId, String capability) {
        if (snapshotId == null) {
            return Mono.error(new IntelligenceException(400, "任务创作必须绑定创作上下文快照"));
        }
        return snapshots.findById(snapshotId)
                .filter(snapshot -> accountId.equals(snapshot.accountId()))
                .switchIfEmpty(Mono.error(new IntelligenceException(403, "无权使用该创作上下文快照")))
                .flatMap(snapshot -> resolveProvider(snapshot, accountId, capability)
                        .map(provider -> new ResolvedSnapshot(snapshot, provider)));
    }

    /**
     * 任务书 #56：解析快照冻结的图像 BYOK 键。{@code imageGeneration} 段为 BYOK 形态时按
     * {@link #resolveByok} 同款漂移语义复查（轮换/禁用/降配 → 409 fail-closed）；平台形态或段落
     * 缺失返回 empty，由调用方回落既有平台 Config 路径（指纹校验不变）。
     */
    public Mono<ProviderResolution> resolveImageProvider(CreationContextSnapshot snapshot, String accountId) {
        Object raw = snapshot.aiConfigSnapshot() == null ? null : snapshot.aiConfigSnapshot().get("imageGeneration");
        if (!(raw instanceof Map<?, ?> map)) {
            return Mono.empty();
        }
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                config.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        if (!"BYOK".equalsIgnoreCase(text(config, "resolutionType"))) {
            return Mono.empty();
        }
        return resolveByok(config, accountId, "image_generation");
    }

    private Mono<ProviderResolution> resolveProvider(
            CreationContextSnapshot snapshot, String accountId, String capability) {
        Map<String, Object> config = snapshot.aiConfigSnapshot();
        String type = text(config, "resolutionType");
        if ("BYOK".equalsIgnoreCase(type)) {
            return resolveByok(config, accountId, capability);
        }
        if ("PLATFORM".equalsIgnoreCase(type) && !"unavailable".equalsIgnoreCase(text(config, "status"))) {
            return resolvePlatform(config, capability);
        }
        return Mono.error(new IntelligenceException(503, "快照中的 AI 配置不可用"));
    }

    private Mono<ProviderResolution> resolveByok(
            Map<String, Object> config, String accountId, String capability) {
        UUID configId = uuid(config, "configId");
        // 个人密钥优先；快照来自组织密钥的 Run 时回落组织分支（ADR-D17）——
        // 重跑者必须仍是该组织成员（identity 权威校验），否则与配置漂移同口径 409 fail-closed。
        // 组织分支整体包在 defer 里：个人命中时不应触碰组织查询（eager-assembly 陷阱）。
        return keys.findPersonalByIdAndOwner(configId, accountId)
                .switchIfEmpty(Mono.defer(() -> keys.findOrgById(configId)
                        .flatMap(key -> orgAuthorization
                                .require(accountId, key.organizationId(), "member")
                                .thenReturn(key))
                        .onErrorMap(IntelligenceException.class, error ->
                                new IntelligenceException(409, FROZEN_BYOK_CHANGED))))
                .filter(AiProviderKey::enabled)
                .filter(key -> capability.equals(key.capability()))
                .filter(key -> equalsText(config, "provider", key.provider()))
                .filter(key -> equalsText(config, "model", key.model()))
                .filter(key -> equalsText(config, "keyVersion", key.keyVersion()))
                .filter(key -> matchesInstant(config.get("configUpdatedAt"), key.updatedAt()))
                .map(key -> ProviderResolution.byok(
                        key.provider(), key.baseUrl(), key.model(), key.encryptedKey(), key.keyVersion(),
                        key.organizationId()))
                .switchIfEmpty(Mono.error(new IntelligenceException(409, FROZEN_BYOK_CHANGED)));
    }

    private Mono<ProviderResolution> resolvePlatform(Map<String, Object> config, String capability) {
        UUID configId = uuid(config, "configId");
        int version = integer(config, "platformModelVersion");
        // 任务书 #58 决策 E：密钥从不入快照——回放时取该配置行「当前有效凭据」的密文（轮换后自动用
        // 新钥）；凭据停用/缺失则密文为 null，由解密层 503 fail-closed（不再回落 env bootstrap）。
        return platformModels.findWithCredentialById(configId)
                .filter(row -> capability.equals(row.config().capability()))
                .filter(row -> row.config().version() == version)
                .filter(row -> equalsText(config, "provider", row.config().provider()))
                .filter(row -> equalsText(config, "model", row.config().model()))
                .map(row -> ProviderResolution.platform(
                        row.config().id(), row.config().provider(), row.effectiveBaseUrl(),
                        row.config().model(), row.config().version(), row.config().maxConcurrency(),
                        row.credentialEncryptedKey(), row.credentialVersion()))
                .switchIfEmpty(Mono.error(new IntelligenceException(
                        409, "创作开始时冻结的平台模型配置已变化或不可用")));
    }

    private static String text(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static boolean equalsText(Map<String, Object> config, String key, String actual) {
        String expected = text(config, key);
        return expected == null ? actual == null : expected.equals(actual);
    }

    private static boolean matchesInstant(Object frozen, Instant actual) {
        if (frozen == null || actual == null) {
            return frozen == null && actual == null;
        }
        try {
            Instant expected = frozen instanceof Number number
                    ? Instant.ofEpochSecond(number.longValue(),
                            Math.round((number.doubleValue() - number.longValue()) * 1_000_000_000D))
                    : Instant.parse(String.valueOf(frozen));
            return expected.equals(actual);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static UUID uuid(Map<String, Object> config, String key) {
        try {
            return UUID.fromString(text(config, key));
        } catch (RuntimeException error) {
            throw new IntelligenceException(409, "创作上下文中的 AI 配置标识不合法");
        }
    }

    private static int integer(Map<String, Object> config, String key) {
        try {
            return Integer.parseInt(text(config, key));
        } catch (RuntimeException error) {
            throw new IntelligenceException(409, "创作上下文中的 AI 配置版本不合法");
        }
    }

    public record ResolvedSnapshot(
            CreationContextSnapshot snapshot,
            ProviderResolution provider) {}
}
