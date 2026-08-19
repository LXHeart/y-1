package com.grassland.marketplace.analytics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class MarketingAttributionWebhookVerifier {
    private final MarketingAttributionProperties properties;

    public MarketingAttributionWebhookVerifier(MarketingAttributionProperties properties) {
        this.properties = properties;
    }

    public Verified verify(String provider, String eventId, String timestamp, String signature,
                           String rawBody, Instant now) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("营销归因 provider 未启用");
        }
        if (blank(provider) || blank(eventId) || blank(timestamp) || blank(signature)
                || rawBody == null || rawBody.isEmpty()) {
            throw new IllegalArgumentException("归因 webhook 头缺失");
        }
        if (eventId.length() > 160) throw new IllegalArgumentException("归因 webhook eventId 过长");
        long signedAt;
        try {
            signedAt = Long.parseLong(timestamp);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("归因 webhook 时间戳无效");
        }
        DurationCheck.check(properties.getWebhookTimestampWindow(), signedAt, now.getEpochSecond());
        String secret = properties.secretFor(provider);
        if (blank(secret) || secret.length() < 32) {
            throw new IllegalStateException("归因 provider webhook secret 未配置");
        }
        String expected = sign(secret, timestamp + "." + eventId + "." + rawBody);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                signature.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("归因 webhook 签名无效");
        }
        return new Verified(provider, eventId, signedAt);
    }

    public static String sign(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("无法计算归因 webhook 签名", error);
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public record Verified(String provider, String eventId, long signedAt) {}

    private static final class DurationCheck {
        static void check(java.time.Duration window, long signedAt, long now) {
            long delta;
            try { delta = Math.subtractExact(now, signedAt); }
            catch (ArithmeticException error) { throw new IllegalArgumentException("归因 webhook 已过期"); }
            if (window == null || window.isNegative() || window.isZero()
                    || delta > window.toSeconds() || delta < -window.toSeconds()) {
                throw new IllegalArgumentException("归因 webhook 已过期");
            }
        }
    }
}
