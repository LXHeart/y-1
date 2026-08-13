package com.grassland.intelligence.videoproduction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Verifies provider callbacks before any task state or billing mutation. */
@Component
public class VideoProviderWebhookVerifier {
    private static final String ALGORITHM = "HmacSHA256";
    private final VideoGenerationProperties properties;

    public VideoProviderWebhookVerifier(VideoGenerationProperties properties) {
        this.properties = properties;
    }

    public Verified verify(String provider, String eventId, String timestamp,
                           String signature, String rawBody, long nowEpochSeconds) {
        if (eventId == null || eventId.isBlank() || timestamp == null || signature == null
                || rawBody == null || rawBody.isEmpty()) {
            throw new IllegalArgumentException("视频 provider 回调头缺失");
        }
        long signedAt;
        try {
            signedAt = Long.parseLong(timestamp);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("视频 provider 回调时间戳无效");
        }
        long window = properties.getWebhookTimestampWindow().toSeconds();
        if (Math.abs(nowEpochSeconds - signedAt) > window) {
            throw new IllegalArgumentException("视频 provider 回调已过期");
        }
        String secret = properties.getWebhookSecret(provider);
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("视频 provider webhook secret 未配置");
        }
        String expected = sign(secret, timestamp + "." + eventId + "." + rawBody);
        if (!constantTimeEquals(expected, signature.trim().toLowerCase())) {
            throw new IllegalArgumentException("视频 provider 回调签名无效");
        }
        return new Verified(provider, eventId, signedAt);
    }

    static String sign(String secret, String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("无法计算 webhook 签名", error);
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    public record Verified(String provider, String eventId, long signedAt) {}
}
