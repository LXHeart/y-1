package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class VideoGenerationPropertiesTest {
    @Test
    void sandboxDoesNotRequireVendorCredentials() {
        VideoGenerationProperties properties = new VideoGenerationProperties();
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void realProviderRequiresSafeEndpointAndPaths() {
        VideoGenerationProperties properties = new VideoGenerationProperties();
        properties.setMode("minimax");
        properties.setBaseUrl("https://api.example.com");
        properties.setApiKey("secret");
        properties.setModel("video-01");
        properties.setCreatePath("/v1/video_generation");
        properties.setPollPath("/v1/query/video_generation");
        properties.setRetrievePath("/v1/files/retrieve");
        properties.setWebhookSecret("w".repeat(32));
        assertThatCode(properties::validate).doesNotThrowAnyException();

        properties.setCreatePath("https://attacker.example/create");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("createPath");
    }

    @Test
    void rejectsPrivateProviderEndpointAndUnsafeBilling() {
        VideoGenerationProperties properties = new VideoGenerationProperties();
        properties.setMode("seedance");
        properties.setBaseUrl("http://127.0.0.1:8080");
        properties.setApiKey("secret");
        properties.setModel("seedance");
        properties.setWebhookSecret("w".repeat(32));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内网");

        properties.setBaseUrl("https://api.example.com");
        properties.setUnitPriceCents(0);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("计价");
    }

    @Test
    void realProviderRequiresStrongWebhookSecretAtStartup() {
        VideoGenerationProperties properties = new VideoGenerationProperties();
        properties.setMode("minimax");
        properties.setBaseUrl("https://api.example.com");
        properties.setApiKey("secret");
        properties.setModel("video-01");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("webhook secret");
    }

    @Test
    void policyStateRequiresAllVersionedFieldsAndReturnsOnlyVersion() {
        VideoGenerationProperties properties = new VideoGenerationProperties();
        assertThat(properties.financeCreditsCentsPolicyState()).isEqualTo("policy_missing");
        properties.setFinanceCreditsCentsPolicyVersion("credits-cents-v1");
        properties.setFinanceCreditsCentsPolicyEffectiveAt("2026-08-13T00:00:00Z");
        properties.setFinanceCreditsCentsPolicyRounding("HALF_UP");
        properties.setFinanceCreditsCentsPolicyCentsNumerator("100");
        properties.setFinanceCreditsCentsPolicyCreditsDenominator("1");
        properties.setFinanceCreditsCentsPolicyMaxCentsPerOperation("100000");
        assertThat(properties.financeCreditsCentsPolicyState()).isEqualTo("credits-cents-v1");
        properties.setFinanceCreditsCentsPolicyRounding("bankers");
        assertThat(properties.financeCreditsCentsPolicyState()).isEqualTo("policy_missing");
    }
}
