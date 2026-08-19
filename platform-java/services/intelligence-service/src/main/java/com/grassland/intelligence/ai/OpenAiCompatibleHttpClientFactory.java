package com.grassland.intelligence.ai;

import com.grassland.http.ManagedWebClientFactory;
import com.grassland.intelligence.ai.controlplane.PlatformProviderPolicy;
import java.time.Duration;
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
        return ManagedWebClientFactory.builder(owner, responseTimeout, maxResponseBytes)
                .baseUrl(withTrailingSlash(invocation.baseUrl()))
                .build();
    }

    private static String withTrailingSlash(String value) {
        return value.endsWith("/") ? value : value + "/";
    }
}
