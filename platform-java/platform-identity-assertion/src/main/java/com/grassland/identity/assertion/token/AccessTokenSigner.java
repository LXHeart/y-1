package com.grassland.identity.assertion.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 移动端 access token 的 HMAC-SHA256 签发/验签（GL-P3-IDENTITY-001）。
 *
 * <p>与 {@link com.grassland.identity.assertion.IdentityAssertionSigner} 同构但<b>单密钥</b>：
 * access token 只有一对签发/验签方（identity-service 签、edge-bff 验），不需要多受众 keyring。
 * {@code kid} 作为 payload claim 保留，供未来密钥轮换（双 kid 并存）扩展。
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

    private final byte[] secret;
    private final String kid;
    private final Duration leeway;

    /**
     * @param secret HMAC 密钥（可为空/空数组 = 未配置，见类注释；建议 ≥32 字节）
     * @param kid 密钥标识（签入 payload，验签须精确匹配；非空）
     * @param leeway 时钟偏差容忍（null → 5s）
     */
    public AccessTokenSigner(byte[] secret, String kid, Duration leeway) {
        this.secret = secret == null ? new byte[0] : secret.clone();
        if (kid == null || kid.isBlank()) {
            throw new IllegalArgumentException("kid must be non-blank");
        }
        this.kid = kid;
        this.leeway = leeway != null ? leeway : Duration.ofSeconds(5);
    }

    /** secret 是否已配置（空 secret = 未配置，签发应 gate、验签降级）。 */
    public boolean isConfigured() {
        return secret.length > 0;
    }

    public String kid() {
        return kid;
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
        AccessToken enveloped = kid.equals(token.kid()) ? token
                : new AccessToken(token.accountId(), token.email(), token.role(), token.deviceId(),
                        token.sessionToken(), kid, token.iat(), token.exp());
        String payload = AccessTokenCodec.encodePayload(enveloped);
        return payload + "." + mac(payload);
    }

    /**
     * 验签 + 校验时间窗与 kid。
     *
     * @param token 待验 token
     * @param now 当前时刻（null → {@link Instant#now()}）
     * @return 验签通过的 claims；任何失败 → {@link Optional#empty()}
     */
    public Optional<AccessToken> verify(String token, Instant now) {
        if (!isConfigured() || token == null || token.isBlank()) {
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

        // MAC 常量时间比较（防时序侧信道）
        String expectedMac = mac(payload);
        if (!MessageDigest.isEqual(
                expectedMac.getBytes(StandardCharsets.UTF_8),
                providedMac.getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }

        // kid 精确匹配（防拿旧密钥签的 token 蒙混）
        if (claims.kid() == null || !claims.kid().equals(kid)) {
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

    private String mac(String payload) {
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
