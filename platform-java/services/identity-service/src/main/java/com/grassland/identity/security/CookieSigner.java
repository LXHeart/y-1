package com.grassland.identity.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 兼容 Node cookie-signature（express-session）的签名/验签。
 * cookie 值格式：s:<sid>.<mac>，其中 mac = base64(HMAC-SHA256(secret, sid)) 去掉末尾 '='。
 */
@Component
public class CookieSigner {
    private final byte[] secretBytes;

    public CookieSigner(@Value("${identity.legacy.session.secret:}") String secret) {
        this.secretBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isConfigured() {
        return secretBytes.length > 0;
    }

    public String sign(String value) {
        return value + "." + mac(value);
    }

    public String signCookie(String sid) {
        return "s:" + sign(sid);
    }

    public Optional<String> unsign(String signedValue) {
        if (signedValue == null || signedValue.isBlank() || secretBytes.length == 0) {
            return Optional.empty();
        }
        String value = signedValue.startsWith("s:") ? signedValue.substring(2) : signedValue;
        int dot = value.indexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        String sid = value.substring(0, dot);
        String provided = value.substring(dot + 1);
        String expected = mac(sid);
        if (MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            return Optional.of(sid);
        }
        return Optional.empty();
    }

    private String mac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest).replaceAll("=+$", "");
        } catch (Exception error) {
            throw new IllegalStateException("HMAC computation failed", error);
        }
    }
}
