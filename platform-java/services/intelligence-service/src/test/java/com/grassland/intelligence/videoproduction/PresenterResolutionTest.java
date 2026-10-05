package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.grassland.intelligence.ai.controlplane.PlatformModelControlPlaneService;
import com.grassland.intelligence.ai.controlplane.PlatformModelControlPlaneService.ResolvedPlatformModel;
import com.grassland.intelligence.ai.run.PriceTableService;
import com.grassland.intelligence.ai.run.ProviderKeyDecryptor;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class PresenterResolutionTest {
    @Test void staticRuntimeCannotMasqueradeAsPresenterEvenWhenEnabled() {
        var control = mock(PlatformModelControlPlaneService.class);
        var keys = mock(ProviderKeyDecryptor.class);
        var prices = mock(PriceTableService.class);
        when(control.resolve("digital_human_render")).thenReturn(Mono.just(Optional.of(new ResolvedPlatformModel(
                UUID.randomUUID(), "runtime-static", "runtime-static-1", "https://runtime.example", 1, "primary", 1))));
        var resolver = new VideoGenerationProviderResolver(control, keys, prices, new VideoGenerationProperties());
        assertThat(resolver.resolvePresenter().block().available()).isFalse();
        verifyNoInteractions(keys, prices);
    }
    @Test void missingCredentialAndMissingPriceBothKeepPresenterUnavailable() {
        var control = mock(PlatformModelControlPlaneService.class);
        var keys = mock(ProviderKeyDecryptor.class);
        var prices = mock(PriceTableService.class);
        when(control.resolve("digital_human_render")).thenReturn(Mono.just(Optional.of(new ResolvedPlatformModel(
                UUID.randomUUID(), "wan", "wan2.2-s2v", "https://provider.example", 1, "primary", 1))));
        var resolver = new VideoGenerationProviderResolver(control, keys, prices, new VideoGenerationProperties());
        assertThat(resolver.resolvePresenter().block().unavailableReason()).contains("凭据");
        when(keys.decryptIfNeeded(any())).thenReturn("test-key");
        when(prices.priceFor(null, "wan2.2-s2v")).thenThrow(new IllegalArgumentException("unpriced"));
        assertThat(resolver.resolvePresenter().block().unavailableReason()).contains("价目");
        reset(prices);
        when(prices.priceFor(null, "wan2.2-s2v")).thenReturn(new com.grassland.intelligence.ai.run.PriceTable.ModelPrice(
                "digital_human_render", "wan", 0, 0, 0, 25));
        when(prices.currentVersionLabel()).thenReturn("presenter-v1");
        var available = resolver.resolvePresenter().block();
        assertThat(available.available()).isTrue();
        assertThat(available.unitPriceCents()).isEqualTo(25);
        assertThat(available.priceTableVersion()).isEqualTo("presenter-v1");
    }
}
