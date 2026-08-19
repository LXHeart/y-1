package com.grassland.intelligence.ai.controlplane;

import com.grassland.intelligence.ai.PlatformModelConfig;
import com.grassland.intelligence.ai.ProviderUrlGuard;
import com.grassland.intelligence.embedding.EmbeddingProviderProperties;
import com.grassland.intelligence.speech.SpeechProviderProperties;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Binds the single platform credential to explicitly trusted Qwen origins. */
@Component
public final class PlatformProviderPolicy {

    private static final String QWEN = "qwen";
    private static final String OPENAI_COMPATIBLE = "openai-compatible";
    private static final String SANDBOX = "sandbox";
    private static final String SANDBOX_BASE_URL = "https://sandbox.invalid";
    private final Set<String> trustedOrigins;
    private final Set<String> trustedOpenAiCompatibleOrigins;
    private final boolean allowInsecureLoopback;

    @Autowired
    public PlatformProviderPolicy(
            PlatformModelConfig defaults,
            @Value("${ai.platform-model.trusted-qwen-origins:https://dashscope.aliyuncs.com}") String configuredOrigins,
            @Value("${ai.platform-model.trusted-openai-compatible-origins:https://api.openai.com}")
                    String configuredOpenAiOrigins,
            SpeechProviderProperties speech,
            EmbeddingProviderProperties embedding,
            @Value("${ai.platform-model.allow-insecure-loopback:false}") boolean allowInsecureLoopback) {
        this(defaults, configuredOrigins, configuredOpenAiOrigins, speech, embedding,
                allowInsecureLoopback, true);
    }

    PlatformProviderPolicy(PlatformModelConfig defaults, String configuredOrigins) {
        this(defaults, configuredOrigins, "https://api.openai.com", null, null, false, true);
    }

    private PlatformProviderPolicy(
            PlatformModelConfig defaults,
            String configuredOrigins,
            String configuredOpenAiOrigins,
            SpeechProviderProperties speech,
            EmbeddingProviderProperties embedding,
            boolean allowInsecureLoopback,
            boolean ignored) {
        this.allowInsecureLoopback = allowInsecureLoopback;
        LinkedHashSet<String> origins = new LinkedHashSet<>();
        origins.add(origin(validateTransport(ProviderUrlGuard.validate(defaults.baseUrl()))));
        Arrays.stream(configuredOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(ProviderUrlGuard::validate)
                .map(this::validateTransport)
                .map(PlatformProviderPolicy::origin)
                .forEach(origins::add);
        this.trustedOrigins = Set.copyOf(origins);
        LinkedHashSet<String> openAiOrigins = configuredOrigins(configuredOpenAiOrigins);
        addConfiguredProviderOrigin(openAiOrigins, speech == null ? null : speech.provider(),
                speech == null ? null : speech.baseUrl());
        addConfiguredProviderOrigin(openAiOrigins, embedding == null ? null : embedding.provider(),
                embedding == null ? null : embedding.baseUrl());
        this.trustedOpenAiCompatibleOrigins = Set.copyOf(openAiOrigins);
    }

    public URI validate(String provider, String baseUrl) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if (SANDBOX.equals(normalized)) {
            URI uri = ProviderUrlGuard.validate(baseUrl);
            if (!SANDBOX_BASE_URL.equals(uri.toString())) {
                throw new IllegalArgumentException("Sandbox provider 只能使用内置地址");
            }
            return uri;
        }
        if (!(QWEN.equals(normalized) || OPENAI_COMPATIBLE.equals(normalized))) {
            throw new IllegalArgumentException("平台 provider 必须是 qwen、openai-compatible 或 sandbox");
        }
        URI uri = validateTransport(ProviderUrlGuard.validate(baseUrl));
        Set<String> allowed = QWEN.equals(normalized) ? trustedOrigins : trustedOpenAiCompatibleOrigins;
        if (!allowed.contains(origin(uri))) {
            throw new IllegalArgumentException("平台模型 base-url 不在对应 provider 的受信地址范围内");
        }
        return uri;
    }

    public URI validateBaseUrl(String baseUrl) {
        return validate(QWEN, baseUrl);
    }

    private LinkedHashSet<String> configuredOrigins(String configured) {
        LinkedHashSet<String> origins = new LinkedHashSet<>();
        Arrays.stream((configured == null ? "" : configured).split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(ProviderUrlGuard::validate)
                .map(this::validateTransport)
                .map(PlatformProviderPolicy::origin)
                .forEach(origins::add);
        return origins;
    }

    private void addConfiguredProviderOrigin(Set<String> origins, String provider, String baseUrl) {
        if (!OPENAI_COMPATIBLE.equals(provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT))
                || baseUrl == null || baseUrl.isBlank()) {
            return;
        }
        origins.add(origin(validateTransport(ProviderUrlGuard.validate(baseUrl))));
    }

    private URI validateTransport(URI uri) {
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return uri;
        }
        if (allowInsecureLoopback
                && "http".equalsIgnoreCase(uri.getScheme())
                && isLoopback(uri.getHost())) {
            return uri;
        }
        throw new IllegalArgumentException("平台模型 base-url 必须使用 HTTPS");
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host);
    }

    private static String origin(URI uri) {
        int port = uri.getPort();
        int effectivePort = port >= 0 ? port : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://"
                + uri.getHost().toLowerCase(Locale.ROOT) + ":" + effectivePort;
    }
}
