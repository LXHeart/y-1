package com.grassland.intelligence.articleimage;

import com.grassland.intelligence.creationcontext.CreationContextSnapshot;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Freezes non-secret image provider metadata and rejects runtime configuration drift. */
@Service
public class FrozenImageGenerationConfigResolver {
    private final ImageGenerationConfig config;

    public FrozenImageGenerationConfigResolver(ImageGenerationConfig config) {
        this.config = config;
    }

    public Map<String, Object> snapshot() {
        Config current = current();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", current.provider());
        result.put("model", current.model());
        result.put("pricingVersion", current.pricingVersion());
        result.put("unitPriceCents", current.unitPriceCents());
        result.put("platformModelVersion", current.platformModelVersion());
        result.put("runtimeFingerprint", current.runtimeFingerprint());
        return result;
    }

    public Config resolve(CreationContextSnapshot snapshot) {
        Object raw = snapshot.aiConfigSnapshot().get("imageGeneration");
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IntelligenceException(409, "创作上下文缺少冻结的图片生成配置");
        }
        Config frozen = new Config(
                text(map, "provider"), text(map, "model"), text(map, "pricingVersion"),
                integer(map, "unitPriceCents"), integer(map, "platformModelVersion"),
                text(map, "runtimeFingerprint"));
        if (!frozen.equals(current())) {
            throw new IntelligenceException(409, "创作开始时冻结的图片生成配置已变化或不可用");
        }
        return frozen;
    }

    public Config current() {
        return new Config(
                config.provider(), config.model(), config.pricingVersion(),
                config.unitPriceCents(), config.platformModelVersion(), fingerprint(config));
    }

    private static String fingerprint(ImageGenerationConfig value) {
        String canonical = String.join("\n",
                normalized(value.provider()), normalized(value.baseUrl()), normalized(value.apiKey()),
                normalized(value.model()), normalized(value.pricingVersion()),
                String.valueOf(value.unitPriceCents()), String.valueOf(value.platformModelVersion()),
                String.valueOf(value.connectTimeout()), String.valueOf(value.readTimeout()));
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
            throw new IntelligenceException(409, "创作上下文中的图片生成配置不完整");
        }
        return String.valueOf(value);
    }

    private static int integer(Map<?, ?> map, String key) {
        try {
            return Integer.parseInt(text(map, key));
        } catch (NumberFormatException error) {
            throw new IntelligenceException(409, "创作上下文中的图片生成配置不合法");
        }
    }

    public record Config(
            String provider, String model, String pricingVersion, int unitPriceCents,
            int platformModelVersion, String runtimeFingerprint) {}
}
