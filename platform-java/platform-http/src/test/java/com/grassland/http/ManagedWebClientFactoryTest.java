package com.grassland.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ManagedWebClientFactoryTest {

    @Test
    void reusesANamedProviderForTheSameClientClass() {
        int before = ManagedWebClientFactory.providerCount();
        ManagedWebClientFactory.create(ManagedWebClientFactoryTest.class, "http://identity-service:8082");
        ManagedWebClientFactory.create(ManagedWebClientFactoryTest.class, "http://identity-service:8082");
        assertThat(ManagedWebClientFactory.providerCount()).isEqualTo(before + 1);
    }

    @Test
    void rejectsInvalidTimeoutAndBlankBaseUrl() {
        assertThatThrownBy(() -> ManagedWebClientFactory.builder(
                ManagedWebClientFactoryTest.class, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ManagedWebClientFactory.builder(
                ManagedWebClientFactoryTest.class, Duration.ofSeconds(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ManagedWebClientFactory.builder(
                ManagedWebClientFactoryTest.class, Duration.ZERO, Duration.ofSeconds(1), 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ManagedWebClientFactory.create(ManagedWebClientFactoryTest.class, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configuredExternalEndpointsRequireHttpsAndHonorTheAllowlist() {
        assertThat(ManagedWebClientFactory.requireConfiguredEndpoint(
                "https://push.example.com/send", false, "push.example.com,sms.example.com"))
                .isEqualTo(URI.create("https://push.example.com/send"));
        assertThatThrownBy(() -> ManagedWebClientFactory.requireConfiguredEndpoint(
                "http://push.example.com/send", false, "push.example.com"))
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> ManagedWebClientFactory.requireConfiguredEndpoint(
                "https://other.example.com/send", false, "push.example.com"))
                .hasMessageContaining("allowlisted");
        assertThatThrownBy(() -> ManagedWebClientFactory.requireConfiguredEndpoint(
                "https://user:pass@push.example.com/send", false, "push.example.com"))
                .hasMessageContaining("no user info");
    }
}
