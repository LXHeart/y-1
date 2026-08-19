package com.grassland.intelligence.ai;

import com.grassland.intelligence.embedding.EmbeddingProviderProperties;
import com.grassland.intelligence.speech.SpeechProviderProperties;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Fails startup when a configured real speech or embedding adapter is incomplete or unsafe. */
@Component
public final class AiCapabilityProviderConfigValidator {

    private static final Set<String> REAL_PROVIDERS = Set.of("qwen", "openai-compatible");
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    private final SpeechProviderProperties speech;
    private final EmbeddingProviderProperties embedding;
    private final boolean allowInsecureLoopback;

    public AiCapabilityProviderConfigValidator(
            SpeechProviderProperties speech,
            EmbeddingProviderProperties embedding,
            @Value("${ai.platform-model.allow-insecure-loopback:false}") boolean allowInsecureLoopback) {
        this.speech = speech;
        this.embedding = embedding;
        this.allowInsecureLoopback = allowInsecureLoopback;
    }

    @PostConstruct
    void validate() {
        validateSpeech(speech, allowInsecureLoopback);
        validateEmbedding(embedding, allowInsecureLoopback);
        if (!speech.sandbox() && !embedding.sandbox()
                && speech.model().trim().equals(embedding.model().trim())) {
            throw new IllegalStateException("Speech 与 Embedding 真实模型名不能相同，价目表按模型名唯一索引");
        }
    }

    static void validateSpeech(SpeechProviderProperties value, boolean allowInsecureLoopback) {
        requireCommon(
                "Speech", value.provider(), value.baseUrl(), value.apiKey(), value.model(),
                value.transcriptionPath(), value.requestTimeout(), value.maxResponseBytes(),
                value.sandbox(), allowInsecureLoopback);
        requireNonNegative("Speech centsPer1kInputTokens", value.centsPer1kInputTokens());
        requireNonNegative("Speech centsPer1kOutputTokens", value.centsPer1kOutputTokens());
        requireNonNegative("Speech centsPerSecond", value.centsPerSecond());
    }

    static void validateEmbedding(EmbeddingProviderProperties value, boolean allowInsecureLoopback) {
        requireCommon(
                "Embedding", value.provider(), value.baseUrl(), value.apiKey(), value.model(),
                value.embeddingsPath(), value.requestTimeout(), value.maxResponseBytes(),
                value.sandbox(), allowInsecureLoopback);
        if (value.dimensions() < 1 || value.dimensions() > 4096) {
            throw new IllegalStateException("Embedding dimensions 必须在 1-4096 之间");
        }
        requireNonNegative("Embedding centsPer1kInputTokens", value.centsPer1kInputTokens());
    }

    private static void requireCommon(
            String capability,
            String provider,
            String baseUrl,
            String apiKey,
            String model,
            String path,
            Duration timeout,
            int maxResponseBytes,
            boolean sandbox,
            boolean allowInsecureLoopback) {
        requirePath(capability, path);
        if (timeout == null || timeout.compareTo(Duration.ofSeconds(1)) < 0
                || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalStateException(capability + " requestTimeout 必须在 1 秒到 5 分钟之间");
        }
        if (maxResponseBytes < 1024 || maxResponseBytes > MAX_RESPONSE_BYTES) {
            throw new IllegalStateException(capability + " maxResponseBytes 必须在 1 KiB 到 16 MiB 之间");
        }
        if (sandbox) {
            if (!"https://sandbox.invalid".equals(baseUrl)) {
                throw new IllegalStateException(capability + " Sandbox 只能使用内置地址");
            }
            return;
        }
        String normalizedProvider = normalize(provider);
        if (!REAL_PROVIDERS.contains(normalizedProvider)) {
            throw new IllegalStateException(capability + " provider 必须是 qwen、openai-compatible 或 sandbox");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(capability + " model 不能为空");
        }
        requireSecret(capability, apiKey);
        URI endpoint = ProviderUrlGuard.validate(baseUrl);
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                && !(allowInsecureLoopback && isLoopback(endpoint.getHost()))) {
            throw new IllegalStateException(capability + " baseUrl 必须使用 HTTPS");
        }
    }

    private static void requirePath(String capability, String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//")
                || path.contains("..") || path.contains("?") || path.contains("#")) {
            throw new IllegalStateException(capability + " Provider path 必须是无查询参数的绝对路径");
        }
    }

    private static void requireSecret(String capability, String apiKey) {
        String normalized = normalize(apiKey);
        if (apiKey == null || apiKey.length() < 16
                || normalized.contains("replace-with")
                || normalized.contains("placeholder")
                || normalized.contains("changeme")
                || normalized.startsWith("your-")) {
            throw new IllegalStateException(capability + " apiKey 不能缺失或使用模板占位值");
        }
    }

    private static void requireNonNegative(String name, int value) {
        if (value < 0) {
            throw new IllegalStateException(name + " 不能为负数");
        }
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
