package com.grassland.edge.internalassertion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** 验证 edge-bff cookie unsign 与 identity-service CookieSigner 同算法（Node cookie-signature 兼容）。 */
class EdgeCookieSignerTest {

    private static final String SECRET = "test-secret-32-chars-minimum!!!";

    /** 复刻 identity-service CookieSigner.sign 的算法签出 cookie，edge 端必须 unsign 回来。 */
    private static String signLikeIdentity(String sid) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(sid.getBytes(StandardCharsets.UTF_8));
            String macValue = Base64.getEncoder().encodeToString(digest).replaceAll("=+$", "");
            return "s:" + sid + "." + macValue;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void unsign_validCookie_returnsSid() {
        var signer = new EdgeCookieSigner(SECRET);
        assertThat(signer.unsign(signLikeIdentity("sid-xyz"))).contains("sid-xyz");
    }

    @Test
    void unsign_tamperedMac_rejected() {
        var signer = new EdgeCookieSigner(SECRET);
        String cookie = signLikeIdentity("sid-xyz");
        assertThat(signer.unsign(cookie + "x")).isEmpty();
    }

    @Test
    void unsign_wrongSecret_rejected() {
        var signer = new EdgeCookieSigner("a-different-secret-32-chars-min!!!");
        assertThat(signer.unsign(signLikeIdentity("sid-xyz"))).isEmpty();
    }

    @Test
    void unsign_unconfigured_rejected() {
        var signer = new EdgeCookieSigner("");
        assertThat(signer.isConfigured()).isFalse();
        assertThat(signer.unsign(signLikeIdentity("sid-xyz"))).isEmpty();
    }

    @Test
    void unsign_malformed_rejected() {
        var signer = new EdgeCookieSigner(SECRET);
        assertThat(signer.unsign("nodothere")).isEmpty();
        assertThat(signer.unsign("")).isEmpty();
        assertThat(signer.unsign(null)).isEmpty();
    }
}
