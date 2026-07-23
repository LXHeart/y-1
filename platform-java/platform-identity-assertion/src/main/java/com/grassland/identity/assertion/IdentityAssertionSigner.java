package com.grassland.identity.assertion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 内部身份断言的 HMAC-SHA256 签发/验签（token 格式 {@code <payloadB64url>.<macB64url>}）。
 *
 * <p>签名对象是 payload 的 <b>base64url 字符串</b>（而非原始 JSON 字节）——与 {@code CookieSigner}
 * 「对值字符串签名」一致，验签端无需重新编码即可复算 MAC。MAC = base64url(HMAC-SHA256(secret, payloadB64url))，
 * 用 {@link MessageDigest#isEqual} 常量时间比较防时序侧信道。
 *
 * <p>{@link #verify} 对<b>任何</b>失败（格式错、MAC 不符、payload 损坏、过期、audience 不符、secret 空）一律返回
 * {@code Optional.empty()}，不抛异常——调用方据此回退 cookie 鉴权（降级而非宕机）。
 *
 * <p>时间窗：{@code issuedAt - leeway ≤ now ≤ expiresAt + leeway}（leeway 容忍 BFF↔服务间时钟偏差）。
 */
public final class IdentityAssertionSigner {

    private final byte[] secret;
    private final String expectedAudience;
    private final Duration leeway;

    public IdentityAssertionSigner(byte[] secret, String expectedAudience, Duration leeway) {
        this.secret = secret == null ? new byte[0] : secret.clone();
        this.expectedAudience = expectedAudience;
        this.leeway = leeway == null ? Duration.ZERO : leeway;
    }

    /** 签发 token。secret 未配置（空）抛 {@link IdentityAssertionException}（编程错误，非运行时降级）。 */
    public String sign(IdentityAssertion assertion) {
        if (secret.length == 0) {
            throw new IdentityAssertionException("identity-assertion signer secret not configured");
        }
        String payload = IdentityAssertionCodec.encodePayload(assertion);
        return payload + "." + mac(payload);
    }

    /**
     * 验签 + 校验时间窗与 audience。{@code now} 传 null 取 {@link Instant#now()}（生产路径）；
     * 测试可传固定时刻断言过期/leeway 行为。
     */
    public Optional<IdentityAssertion> verify(String token, Instant now) {
        if (token == null || token.isBlank() || secret.length == 0) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot >= token.length() - 1) {
            return Optional.empty();
        }
        String payload = token.substring(0, dot);
        String providedMac = token.substring(dot + 1);
        String expectedMac;
        try {
            expectedMac = mac(payload);
        } catch (IdentityAssertionException ignored) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(
                expectedMac.getBytes(StandardCharsets.UTF_8),
                providedMac.getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        IdentityAssertion assertion;
        try {
            assertion = IdentityAssertionCodec.decodePayload(payload);
        } catch (IdentityAssertionException ignored) {
            return Optional.empty();
        }
        Instant when = now == null ? Instant.now() : now;
        if (when.isBefore(assertion.issuedAt().minus(leeway))
                || when.isAfter(assertion.expiresAt().plus(leeway))) {
            return Optional.empty();
        }
        if (expectedAudience != null && !expectedAudience.equals(assertion.audience())) {
            return Optional.empty();
        }
        return Optional.of(assertion);
    }

    private String mac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return IdentityAssertionCodec.URL_ENCODER.encodeToString(digest);
        } catch (Exception error) {
            throw new IdentityAssertionException("HMAC computation failed", error);
        }
    }
}
