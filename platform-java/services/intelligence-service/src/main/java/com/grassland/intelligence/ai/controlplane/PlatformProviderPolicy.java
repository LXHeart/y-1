package com.grassland.intelligence.ai.controlplane;

import com.grassland.intelligence.ai.PlatformModelConfig;
import com.grassland.intelligence.ai.ProviderUrlGuard;
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
    private final Set<String> trustedOrigins;
    private final boolean allowInsecureLoopback;

    @Autowired
    public PlatformProviderPolicy(
            PlatformModelConfig defaults,
            @Value("${ai.platform-model.trusted-qwen-origins:https://dashscope.aliyuncs.com}") String configuredOrigins,
            @Value("${ai.platform-model.allow-insecure-loopback:false}") boolean allowInsecureLoopback) {
        this(defaults, configuredOrigins, allowInsecureLoopback, true);
    }

    PlatformProviderPolicy(PlatformModelConfig defaults, String configuredOrigins) {
        this(defaults, configuredOrigins, false, true);
    }

    private PlatformProviderPolicy(
            PlatformModelConfig defaults,
            String configuredOrigins,
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
    }

    public URI validate(String provider, String baseUrl) {
        if (!QWEN.equals(provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("平台当前只配置了 qwen 凭据，provider 必须是 qwen");
        }
        URI uri = validateTransport(ProviderUrlGuard.validate(baseUrl));
        if (!trustedOrigins.contains(origin(uri))) {
            throw new IllegalArgumentException("平台模型 base-url 不在受信 Qwen 地址范围内");
        }
        return uri;
    }

    public URI validateBaseUrl(String baseUrl) {
        return validate(QWEN, baseUrl);
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
