package com.grassland.marketplace.analytics;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Provider webhook credentials are deployment secrets, never stored in the database. */
@ConfigurationProperties("marketplace.marketing.attribution")
public class MarketingAttributionProperties {
    private boolean enabled = true;
    private Duration webhookTimestampWindow = Duration.ofMinutes(5);
    private Map<String, String> webhookSecrets = new LinkedHashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getWebhookTimestampWindow() { return webhookTimestampWindow; }
    public void setWebhookTimestampWindow(Duration value) { this.webhookTimestampWindow = value; }
    public Map<String, String> getWebhookSecrets() { return webhookSecrets; }
    public void setWebhookSecrets(Map<String, String> value) {
        this.webhookSecrets = new LinkedHashMap<>();
        if (value != null) value.forEach((key, secret) -> this.webhookSecrets.put(normalize(key), secret));
    }

    public String secretFor(String provider) {
        return webhookSecrets.get(normalize(provider));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
