package com.grassland.edge.internalassertion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 兼容 Node cookie-signature（express-session）的验签。edge-bff 局部实现，逻辑与 identity-service 的
 * {@code CookieSigner} 完全一致（cookie 值 {@code s:<sid>.<mac>}，mac = base64(HMAC-SHA256(secret, sid)) 去尾 '='）。
 *
 * <p>不共享 identity-service 的 bean：edge-bff 是独立部署单元，且 identity 的 CookieSigner 耦合其 bean 体系；
 * 本类只做 unsign（edge 不签发 cookie），故局部保留更清晰（strangler 过渡期组件，identity 自管会话后两端统一）。
 */
public final class EdgeCookieSigner {

    private final byte[] secretBytes;

    public EdgeCookieSigner(String secret) {
        this.secretBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isConfigured() {
        return secretBytes.length > 0;
    }

    /** 验签 cookie 值；失败/未配置返回 empty（匿名）。 */
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
