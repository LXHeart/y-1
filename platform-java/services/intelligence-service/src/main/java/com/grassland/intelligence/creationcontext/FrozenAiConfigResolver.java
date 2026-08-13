package com.grassland.intelligence.creationcontext;

import com.grassland.intelligence.ai.byok.AiProviderKey;
import com.grassland.intelligence.ai.byok.AiProviderKeyRepository;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.ai.controlplane.PlatformModelConfigRepository;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Resolves the exact AI configuration captured by a PRD 4.12 creation snapshot. */
@Service
public class FrozenAiConfigResolver {
    private final CreationContextSnapshotRepository snapshots;
    private final AiProviderKeyRepository keys;
    private final PlatformModelConfigRepository platformModels;

    public FrozenAiConfigResolver(CreationContextSnapshotRepository snapshots,
                                  AiProviderKeyRepository keys,
                                  PlatformModelConfigRepository platformModels) {
        this.snapshots = snapshots;
        this.keys = keys;
        this.platformModels = platformModels;
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
        return keys.findPersonalByIdAndOwner(configId, accountId)
                .filter(AiProviderKey::enabled)
                .filter(key -> capability.equals(key.capability()))
                .filter(key -> equalsText(config, "provider", key.provider()))
                .filter(key -> equalsText(config, "model", key.model()))
                .filter(key -> equalsText(config, "keyVersion", key.keyVersion()))
                .filter(key -> matchesInstant(config.get("configUpdatedAt"), key.updatedAt()))
                .map(key -> ProviderResolution.byok(
                        key.provider(), key.baseUrl(), key.model(), key.encryptedKey()))
                .switchIfEmpty(Mono.error(new IntelligenceException(
                        409, "创作开始时冻结的 BYOK 配置已变化或不可用")));
    }

    private Mono<ProviderResolution> resolvePlatform(Map<String, Object> config, String capability) {
        UUID configId = uuid(config, "configId");
        int version = integer(config, "platformModelVersion");
        return platformModels.findById(configId)
                .filter(model -> capability.equals(model.capability()))
                .filter(model -> model.version() == version)
                .filter(model -> equalsText(config, "provider", model.provider()))
                .filter(model -> equalsText(config, "model", model.model()))
                .map(model -> ProviderResolution.platform(
                        model.id(), model.provider(), model.baseUrl(), model.model(),
                        model.version(), model.maxConcurrency()))
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
