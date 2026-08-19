package com.grassland.marketplace.analytics;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MarketingAttributionWebhookVerifierTest {
    private static final String SECRET = "marketing-test-webhook-secret-value-32";
    private final MarketingAttributionProperties properties = properties();
    private final MarketingAttributionWebhookVerifier verifier =
            new MarketingAttributionWebhookVerifier(properties);

    @Test
    void acceptsFreshHmac() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String body = "{\"eventType\":\"exposure\"}";
        String signature = MarketingAttributionWebhookVerifier.sign(SECRET, timestamp + ".evt-1." + body);

        assertThat(verifier.verify("meta", "evt-1", timestamp, signature, body, Instant.now()).provider())
                .isEqualTo("meta");
    }

    @Test
    void rejectsWrongSignature() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        assertThatThrownBy(() -> verifier.verify("meta", "evt-1", timestamp, "bad", "{}", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("签名无效");
    }

    @Test
    void rejectsExpiredTimestamp() {
        String timestamp = String.valueOf(Instant.now().minusSeconds(301).getEpochSecond());
        String body = "{}";
        String signature = MarketingAttributionWebhookVerifier.sign(SECRET, timestamp + ".evt-1." + body);
        assertThatThrownBy(() -> verifier.verify("meta", "evt-1", timestamp, signature, body, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("已过期");
    }

    @Test
    void rejectsTimestampArithmeticOverflow() {
        String timestamp = String.valueOf(Long.MIN_VALUE);
        String body = "{}";
        String signature = MarketingAttributionWebhookVerifier.sign(SECRET, timestamp + ".evt-1." + body);
        assertThatThrownBy(() -> verifier.verify("meta", "evt-1", timestamp, signature, body, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("已过期");
    }

    @Test
    void rejectsMissingSecret() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        assertThatThrownBy(() -> verifier.verify("unknown", "evt-1", timestamp, "sig", "{}", Instant.now()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("secret");
    }

    private static MarketingAttributionProperties properties() {
        MarketingAttributionProperties properties = new MarketingAttributionProperties();
        properties.setWebhookTimestampWindow(Duration.ofMinutes(5));
        properties.setWebhookSecrets(Map.of("META", SECRET));
        return properties;
    }
}
