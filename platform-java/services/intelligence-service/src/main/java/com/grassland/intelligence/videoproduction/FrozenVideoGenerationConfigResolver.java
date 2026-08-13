package com.grassland.intelligence.videoproduction;

import com.grassland.intelligence.creationcontext.CreationContextSnapshot;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Freezes non-secret video provider metadata and rejects runtime configuration drift. */
@Service
public class FrozenVideoGenerationConfigResolver {
    private final VideoGenerationProperties properties;

    public FrozenVideoGenerationConfigResolver(VideoGenerationProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> snapshot() {
        Config config = current();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", config.provider());
        result.put("model", config.model());
        result.put("pricingVersion", config.pricingVersion());
        result.put("unitPriceCents", config.unitPriceCents());
        result.put("platformModelVersion", config.platformModelVersion());
        result.put("maxConcurrency", config.maxConcurrency());
        result.put("maxDurationSeconds", config.maxDurationSeconds());
        result.put("runtimeFingerprint", config.runtimeFingerprint());
        return result;
    }

    public Config resolve(CreationContextSnapshot snapshot) {
        Object raw = snapshot.aiConfigSnapshot().get("videoGeneration");
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IntelligenceException(409, "创作上下文缺少冻结的视频生成配置");
        }
        Config frozen = new Config(
                text(map, "provider"), text(map, "model"), text(map, "pricingVersion"),
                integer(map, "unitPriceCents"), integer(map, "platformModelVersion"),
                integer(map, "maxConcurrency"), integer(map, "maxDurationSeconds"),
                text(map, "runtimeFingerprint"));
        if (!frozen.equals(current())) {
            throw new IntelligenceException(409, "创作开始时冻结的视频生成配置已变化或不可用");
        }
        return frozen;
    }

    public Config resolve(VideoGenerationJob job) {
        Config current = current();
        if (!current.provider().equalsIgnoreCase(job.provider())
                || !current.model().equals(job.model())
                || !current.pricingVersion().equals(job.pricingVersion())
                || current.unitPriceCents() != job.unitPriceCents()
                || current.platformModelVersion() != job.platformModelVersion()
                || (job.providerConfigFingerprint() != null
                    && !current.runtimeFingerprint().equals(job.providerConfigFingerprint()))) {
            throw new IntelligenceException(409, "视频任务创建后 provider 配置已变化，拒绝使用当前配置执行");
        }
        return current;
    }

    public Config current() {
        if (!properties.available()) {
            throw new IntelligenceException(503, properties.unavailableReason());
        }
        return new Config(
                properties.getMode().trim().toLowerCase(Locale.ROOT),
                properties.getModel(), properties.getPricingVersion(),
                properties.getUnitPriceCents(), properties.getPlatformModelVersion(),
                properties.getMaxConcurrency(), properties.getMaxDurationSeconds(), fingerprint(properties));
    }

    private static String fingerprint(VideoGenerationProperties value) {
        String canonical = String.join("\n",
                normalized(value.getMode()), normalized(value.getBaseUrl()), normalized(value.getApiKey()),
                normalized(value.getModel()), normalized(value.resolvedCreatePath()),
                normalized(value.resolvedPollPath()), normalized(value.getRetrievePath()),
                String.valueOf(value.getDefaultDurationSeconds()), String.valueOf(value.getMaxDurationSeconds()),
                String.valueOf(value.getUnitPriceCents()), normalized(value.getPricingVersion()),
                String.valueOf(value.getPlatformModelVersion()), String.valueOf(value.getMaxConcurrency()),
                String.valueOf(value.getRequestTimeout()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IntelligenceException(409, "创作上下文中的视频生成配置不完整");
        }
        return String.valueOf(value);
    }

    private static int integer(Map<?, ?> map, String key) {
        try {
            return Integer.parseInt(text(map, key));
        } catch (NumberFormatException error) {
            throw new IntelligenceException(409, "创作上下文中的视频生成配置不合法");
        }
    }

    public record Config(
            String provider, String model, String pricingVersion, int unitPriceCents,
            int platformModelVersion, int maxConcurrency, int maxDurationSeconds,
            String runtimeFingerprint) {
    }
}
