package com.grassland.intelligence.ai;

import com.grassland.http.ManagedWebClientFactory;
import com.grassland.intelligence.ai.controlplane.PlatformProviderPolicy;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/** Creates bounded clients for trusted platform endpoints or DNS-pinned BYOK endpoints. */
@Component
public final class OpenAiCompatibleHttpClientFactory {

    private final DnsPinningResolver dnsPinning;
    private final PlatformProviderPolicy platformProviderPolicy;

    public OpenAiCompatibleHttpClientFactory(
            DnsPinningResolver dnsPinning,
            PlatformProviderPolicy platformProviderPolicy) {
        this.dnsPinning = dnsPinning;
        this.platformProviderPolicy = platformProviderPolicy;
    }

    public WebClient create(
            Class<?> owner,
            ProviderInvocation invocation,
            Duration responseTimeout,
            int maxResponseBytes) {
        if (invocation.byok()) {
            return PinnedByokClients.forBaseUrl(
                    invocation.baseUrl(), dnsPinning, responseTimeout, maxResponseBytes);
        }
        platformProviderPolicy.validate(invocation.provider(), invocation.baseUrl());
        return pinnedPlatformClient(owner, invocation.baseUrl(), dnsPinning, responseTimeout, maxResponseBytes);
    }

    /**
     * 平台 provider 出站固定连接（GL-P3-AI-001 尾巴：DnsPinningResolver 接进出站 WebClient）。
     *
     * <p>URL 已过 {@link PlatformProviderPolicy} 校验；此处再把连接地址固定——env 固定表
     * （AI_TRUSTED_DOMAINS）优先，否则创建时系统 DNS 解析一次并固定到本客户端生命周期。校验与
     * 连接之间不再有系统 DNS 参与，DNS rebinding 的 TOCTOU 窗口关闭（与 BYOK 路径同口径）。
     * 平台地址是运营配置（可为内网代理），不做公网地址限制——与 BYOK 的公网校验语义不同。
     */
    public static WebClient pinnedPlatformClient(
            Class<?> owner, String baseUrl, DnsPinningResolver dnsPinning,
            Duration responseTimeout, int maxResponseBytes) {
        java.net.URI target = java.net.URI.create(baseUrl == null ? "" : baseUrl.trim());
        String host = target.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("平台 provider 地址缺少 host");
        }
        java.util.List<java.net.InetAddress> addresses =
                new java.util.ArrayList<>(dnsPinning.getPinnedAddresses(host));
        if (addresses.isEmpty()) {
            try {
                addresses.addAll(java.util.List.of(java.net.InetAddress.getAllByName(host)));
            } catch (java.net.UnknownHostException error) {
                throw new IllegalArgumentException("平台 provider 地址无法解析：" + host, error);
            }
        }
        return ManagedWebClientFactory.builder(
                        owner, Duration.ofSeconds(3), responseTimeout, maxResponseBytes,
                        PinnedAddressResolverGroup.forHost(host, List.copyOf(addresses)))
                .baseUrl(withTrailingSlash(target.toString()))
                .build();
    }

    private static String withTrailingSlash(String value) {
        return value.endsWith("/") ? value : value + "/";
    }
}
