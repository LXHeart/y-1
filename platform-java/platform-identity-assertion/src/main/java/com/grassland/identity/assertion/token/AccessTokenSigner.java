package com.grassland.identity.assertion.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 移动端 access token 的 HMAC-SHA256 签发/验签（GL-P3-IDENTITY-001）。
 *
 * <p>签发只用当前密钥，验签按 payload {@code kid} 从 keyring 精确选钥。轮换时 identity-service
 * 切到新 signing kid，edge-bff 同时保留新旧 verify key，旧 key 至少保留 token TTL + leeway。
 *
 * <h3>Token 格式</h3>
 * {@code <payloadB64url>.<macB64url>}，MAC = base64url(HMAC-SHA256(secret, payloadB64url))。
 *
 * <h3>空 secret 约定</h3>
 * 与断言库一致：构造不抛，{@link #isConfigured()} 返回 false，{@link #sign} 抛
 * {@link IllegalStateException}（编程错误，调用方须先 gate），{@link #verify} 返回
 * {@link Optional#empty()}（调用方据此降级为匿名）。
 *
 * <h3>验签铁律</h3>
 * 任何失败（篡改/过期/kid 不符/畸形）一律返回 {@link Optional#empty()}，不抛——调用方据此回退。
 *
 * <h3>时间窗</h3>
 * {@code iat - leeway ≤ now ≤ exp + leeway}。
 */
public final class AccessTokenSigner {

    private final byte[] signingSecret;
    private final String signingKid;
    private final Map<String, byte[]> verificationKeys;
    private final Duration leeway;

    /**
     * @param secret HMAC 密钥（可为空/空数组 = 未配置，见类注释；建议 ≥32 字节）
     * @param kid 密钥标识（签入 payload，验签须精确匹配；非空）
     * @param leeway 时钟偏差容忍（null → 5s）
     */
    public AccessTokenSigner(byte[] secret, String kid, Duration leeway) {
        this(secret, kid, Map.of(), leeway);
    }

    /**
     * @param verificationKeys 额外验签密钥（通常是 previous kid）；当前 signing key 会自动加入并优先。
     */
    public AccessTokenSigner(byte[] secret, String kid, Map<String, byte[]> verificationKeys, Duration leeway) {
        this.signingSecret = secret == null ? new byte[0] : secret.clone();
        if (kid == null || kid.isBlank()) {
            throw new IllegalArgumentException("kid must be non-blank");
        }
        this.signingKid = kid;
        Map<String, byte[]> keys = new LinkedHashMap<>();
        if (verificationKeys != null) {
            verificationKeys.forEach((keyId, keySecret) -> {
                if (keyId == null || keyId.isBlank()) {
                    throw new IllegalArgumentException("verification key kid must be non-blank");
                }
                if (keySecret != null && keySecret.length > 0) {
                    keys.put(keyId, keySecret.clone());
                }
            });
        }
        if (signingSecret.length > 0) {
            keys.put(signingKid, signingSecret.clone());
        }
        this.verificationKeys = Map.copyOf(keys);
        this.leeway = leeway != null ? leeway : Duration.ofSeconds(5);
    }

    /** secret 是否已配置（空 secret = 未配置，签发应 gate、验签降级）。 */
    public boolean isConfigured() {
        return signingSecret.length > 0;
    }

    public String kid() {
        return signingKid;
    }

    /**
     * 签发 token。
     *
     * <p>payload 的 {@code kid} claim 一律以本 signer 的 kid 覆写（同断言 keyring 的 envelope 填充）：
     * 防止签发方误填旧 kid 导致验签方选错密钥/误拒。
     *
     * @throws IllegalStateException secret 未配置（调用方应先 {@link #isConfigured()} gate）
     */
    public String sign(AccessToken token) {
        if (!isConfigured()) {
            throw new IllegalStateException("access token signer secret not configured");
        }
        AccessToken enveloped = signingKid.equals(token.kid()) ? token
                : new AccessToken(token.accountId(), token.email(), token.role(), token.deviceId(),
                        token.sessionToken(), signingKid, token.iat(), token.exp());
        String payload = AccessTokenCodec.encodePayload(enveloped);
        return payload + "." + mac(signingSecret, payload);
    }

    /**
     * 验签 + 校验时间窗与 kid。
     *
     * @param token 待验 token
     * @param now 当前时刻（null → {@link Instant#now()}）
     * @return 验签通过的 claims；任何失败 → {@link Optional#empty()}
     */
    public Optional<AccessToken> verify(String token, Instant now) {
        if (verificationKeys.isEmpty() || token == null || token.isBlank()) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot >= token.length() - 1) {
            return Optional.empty();
        }
        String payload = token.substring(0, dot);
        String providedMac = token.substring(dot + 1);

        AccessToken claims;
        try {
            claims = AccessTokenCodec.decodePayload(payload);
        } catch (AccessTokenException ignored) {
            return Optional.empty();
        }

        if (claims.kid() == null) {
            return Optional.empty();
        }
        byte[] verificationSecret = verificationKeys.get(claims.kid());
        if (verificationSecret == null) {
            return Optional.empty();
        }

        // 按 kid 精确选钥后做 MAC 常量时间比较（防时序侧信道）
        String expectedMac = mac(verificationSecret, payload);
        if (!MessageDigest.isEqual(
                expectedMac.getBytes(StandardCharsets.UTF_8),
                providedMac.getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }

        // 时间窗
        Instant when = now != null ? now : Instant.now();
        if (when.isBefore(claims.issuedAt().minus(leeway))
                || when.isAfter(claims.expiresAt().plus(leeway))) {
            return Optional.empty();
        }

        return Optional.of(claims);
    }

    private String mac(byte[] secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return AccessTokenCodec.URL_ENCODER.encodeToString(digest);
        } catch (Exception error) {
            throw new AccessTokenException("HMAC computation failed", error);
        }
    }
}
