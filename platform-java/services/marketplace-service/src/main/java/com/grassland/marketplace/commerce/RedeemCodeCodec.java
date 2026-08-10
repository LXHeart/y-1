package com.grassland.marketplace.commerce;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Deterministic high-entropy redeem code; only its SHA-256 hash is persisted. */
@Component
public class RedeemCodeCodec {

    private final byte[] secret;

    public RedeemCodeCodec(
            @Value("${marketplace.commerce.redeem-code-secret:local-commerce-redeem-secret-change-me}")
            String secret) {
        if (secret == null || secret.length() < 24) {
            throw new IllegalStateException("marketplace commerce redeem-code-secret must be at least 24 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String codeForOrder(String orderId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            String token = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(orderId.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 20).toUpperCase();
            return "GL-" + token.substring(0, 5) + "-" + token.substring(5, 10)
                    + "-" + token.substring(10, 15) + "-" + token.substring(15, 20);
        } catch (Exception error) {
            throw new IllegalStateException("cannot generate redeem code", error);
        }
    }

    public String hash(String code) {
        try {
            String normalized = code == null ? "" : code.trim().toUpperCase();
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("cannot hash redeem code", error);
        }
    }
}
