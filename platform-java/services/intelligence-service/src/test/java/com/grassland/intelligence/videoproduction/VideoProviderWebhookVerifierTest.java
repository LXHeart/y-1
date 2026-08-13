package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class VideoProviderWebhookVerifierTest {
    @Test
    void verifiesFreshSignedBody() {
        VideoGenerationProperties properties = new VideoGenerationProperties();
        properties.setWebhookSecret("secret");
        VideoProviderWebhookVerifier verifier = new VideoProviderWebhookVerifier(properties);
        String signature = VideoProviderWebhookVerifier.sign("secret", "1000.event.{\"status\":\"succeeded\"}");
        var result = verifier.verify("minimax", "event", "1000", signature,
                "{\"status\":\"succeeded\"}", 1001);
        assertThat(result.eventId()).isEqualTo("event");
    }

    @Test
    void rejectsReplayWindowAndBadSignature() {
        VideoGenerationProperties properties = new VideoGenerationProperties();
        properties.setWebhookSecret("secret");
        VideoProviderWebhookVerifier verifier = new VideoProviderWebhookVerifier(properties);
        assertThatThrownBy(() -> verifier.verify("minimax", "event", "1000", "bad", "body", 2000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("过期");
        assertThatThrownBy(() -> verifier.verify("minimax", "event", "1000", "bad", "body", 1001))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("签名");
    }
}
