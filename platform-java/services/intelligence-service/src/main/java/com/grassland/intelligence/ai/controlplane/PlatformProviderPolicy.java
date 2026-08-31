package com.grassland.intelligence.ai.controlplane;

import com.grassland.intelligence.ai.ProviderUrlGuard;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 平台 provider base-url 的受信校验（SSRF 闸门，任务书 #58 起以 {@code platform_trusted_origin}
 * 表为唯一真相源）。
 *
 * <p>历史形态是「构造期 final Set」：qwen 锚点来自 qwen base-url env、openai-compatible
 * 来自 {@code ai.platform-model.trusted-*-origins} env。env 去 config 化后锚点搬进 origin 表
 * （V56 种子两行 = 原内置默认），本类改读 {@link TrustedOriginService} 的进程内缓存——
 * 治理台增删 origin <b>写后即生效</b>（同 JVM 失效事件），无需重启。
 *
 * <p>所有非 sandbox 方言共用同一张 origin 表（表本身不区分 provider，label 备注用途）：
 * SSRF 的信任对象是「目的地」，与平台选用哪个 provider 方言无关。新增方言不放松白名单口径——
 * 目的地 origin 仍须先由治理台入表，否则 {@link UntrustedPlatformOriginException}。
 */
@Component
public final class PlatformProviderPolicy {

    /** 受支持的平台 provider（= 协议方言名）；值集与 DTO 正则同源见 {@link PlatformProviderNames}。 */
    private static final Set<String> SUPPORTED_PROVIDERS = PlatformProviderNames.ORIGIN_CHECKED;

    private static final String DEFAULT_PROVIDER = PlatformProviderNames.OPENAI_COMPLETIONS;
    private static final String SANDBOX = PlatformProviderNames.SANDBOX;
    private static final String SANDBOX_BASE_URL = "https://sandbox.invalid";

    private final TrustedOriginService trustedOrigins;
    private final boolean allowInsecureLoopback;

    @Autowired
    public PlatformProviderPolicy(
            TrustedOriginService trustedOrigins,
            @Value("${ai.platform-model.allow-insecure-loopback:false}") boolean allowInsecureLoopback) {
        this.trustedOrigins = trustedOrigins;
        this.allowInsecureLoopback = allowInsecureLoopback;
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
        if (!SUPPORTED_PROVIDERS.contains(normalized)) {
            throw new IllegalArgumentException(PlatformProviderNames.MESSAGE);
        }
        URI uri = validateTransport(ProviderUrlGuard.validate(baseUrl));
        String origin = originOf(uri);
        if (!trustedOrigins.enabledOrigins().contains(origin)) {
            throw new UntrustedPlatformOriginException(origin);
        }
        return uri;
    }

    /** 只关心目的地是否受信、不关心方言的调用方入口（provider 取默认方言，校验口径与 {@link #validate} 一致）。 */
    public URI validateBaseUrl(String baseUrl) {
        return validate(DEFAULT_PROVIDER, baseUrl);
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

    /**
     * 归一 origin：{@code scheme://host[:port]}，缺省端口按 scheme 补齐（https=443/http=80）。
     * 表行与校验值都过这一层，「https://x.com」与「https://x.com:443」视为同一端点。
     */
    public static String originOf(URI uri) {
        int port = uri.getPort();
        int effectivePort = port >= 0 ? port : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://"
                + uri.getHost().toLowerCase(Locale.ROOT) + ":" + effectivePort;
    }
}
