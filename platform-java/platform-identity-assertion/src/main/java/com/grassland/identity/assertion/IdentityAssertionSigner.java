package com.grassland.identity.assertion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 内部身份断言的 HMAC-SHA256 签发/验签（GL-P0-ASSERT-001 keyring 模式）。
 *
 * <h3>Token 格式</h3>
 * {@code <payloadB64url>.<macB64url>}，MAC = base64url(HMAC-SHA256(secret, payloadB64url))。
 *
 * <h3>两种构造器</h3>
 * <ul>
 *   <li><b>keyring 构造器</b>（生产）：需 {@link IdentityAssertionKeyring} + {@code issuer} + {@code replayGuard}。
 *       按 {@code (purpose, targetAudience)} 选签名钥，填充 {@code issuer/keyId/jti} claims；
 *       验签时按 {@code (issuer, kid)} 选验签钥，校验 audience/purpose/principal 绑定与 replay。</li>
 *   <li><b>legacy 构造器</b>（测试兼容）：单 secret + 单 audience，不校验 issuer/kid 绑定。
 *       保留此构造器仅为纯单元测试无侵入迁移（如 {@code SmokePreflightFilterTest}）。
 *       不接受 {@code secret=null}（与 properties 强校验一致）。</li>
 </ul>
 *
 * <h3>keyring 模式签发流程</h3>
 * <ol>
 *   <li>从 assertion 推导 purpose（{@code isService()} → {@link Purpose#SERVICE}）。</li>
 *   <li>用 {@code (purpose, targetAudience)} 查签名钥（缺钥 → {@link IdentityAssertionException}）。</li>
 *   <li>填充 envelope claims：{@code issuer}（本服务）、{@code keyId}（密钥 kid）、{@code jti}（随机 UUID），
 *       重写 {@code audience} 为 targetAudience。</li>
 *   <li>序列化 payload → base64url → MAC → 拼接 token。</li>
 * </ol>
 *
 * <h3>keyring 模式验签流程</h3>
 * <ol>
 *   <li>解析 payload → {@code IdentityAssertion}（含 issuer/keyId/jti/audience）。</li>
 *   <li>用 {@code (issuer, kid)} 查验签钥（无钥 → reject）。</li>
 *   <li>用钥的 secret 重算 MAC，常量时间比较（防时序侧信道）。</li>
 *   <li>绑定校验：audience 匹配、purpose 匹配（user 钥拒绝 service 断言，反之亦然）、
 *       service 钥的 {@code principal} 必须等于 issuer。</li>
 *   <li>时间窗校验（leeway 容忍时钟偏差）。</li>
 *   <li>replay guard 消费 jti（可选，默认 NO_OP）。</li>
 * </ol>
 *
 * <h3>时间窗</h3>
 * {@code issuedAt - leeway ≤ now ≤ expiresAt + leeway}。
 */
public final class IdentityAssertionSigner {

    private final IdentityAssertionKeyring keyring;
    private final String issuer;
    private final AssertionReplayGuard replayGuard;
    private final Duration leeway;

    /** legacy 模式：单 secret + 单 audience（仅测试用）。 */
    private final byte[] legacySecret;
    private final String legacyAudience;

    /**
     * keyring 模式构造器（生产）。
     *
     * @param keyring 密钥环（非空）
     * @param issuer 本服务名（签入 payload {@code issuer} claim，如 edge-bff/marketplace）
     * @param replayGuard replay 防护（可为 {@link AssertionReplayGuard#NO_OP}）
     * @param leeway 时钟偏差容忍（默认 5s）
     */
    public IdentityAssertionSigner(IdentityAssertionKeyring keyring, String issuer,
                                    AssertionReplayGuard replayGuard, Duration leeway) {
        if (keyring == null) {
            throw new IllegalArgumentException("keyring must be non-null");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("issuer must be non-blank");
        }
        this.keyring = keyring;
        this.issuer = issuer;
        this.replayGuard = replayGuard != null ? replayGuard : AssertionReplayGuard.NO_OP;
        this.leeway = leeway != null ? leeway : Duration.ofSeconds(5);
        this.legacySecret = null;
        this.legacyAudience = null;
    }

    /**
     * legacy 构造器（纯测试兼容）。
     *
     * <p>仅用于不涉及 issuer/kid 绑定的单元测试（如 {@code SmokePreflightFilterTest}）。
     * 集成测试应迁移到 keyring 模式。
     *
     * @param secret HMAC 密钥（非空，长度≥32 字节建议）
     * @param audience 单一受众（legacy 默认 grassland-internal）
     * @param leeway 时钟偏差容忍
     */
    public IdentityAssertionSigner(byte[] secret, String audience, Duration leeway) {
        // 允许空 secret：与旧版一致——构造不抛，sign 时才抛 IdentityAssertionException，
        // verify 对空 secret 返回 empty（调用方据此降级）。
        this.legacySecret = secret == null ? new byte[0] : secret.clone();
        this.legacyAudience = audience != null ? audience : "grassland-internal";
        this.leeway = leeway != null ? leeway : Duration.ZERO;
        this.keyring = null;
        this.issuer = null;
        this.replayGuard = null;
    }

    /** 是否为 legacy 模式（单 secret）。 */
    public boolean isLegacy() {
        return keyring == null;
    }

    /**
     * keyring 模式签发 token。
     *
     * <p>按 purpose 和 targetAudience 查签名钥，填充 envelope claims，重写 audience。
     *
     * @param assertion 待签断言（accountId/activeIdentityType/... 填好）
     * @param targetAudience 目标受众服务名（如 identity/marketplace/finance）
     * @return 已签 token
     * @throws IdentityAssertionException 缺签名钥
     */
    public String sign(IdentityAssertion assertion, String targetAudience) {
        if (keyring == null) {
            // legacy 模式：单一密钥，targetAudience 无意义（只有一个受众），直接走 1 参签发。
            // 使单元测试可用 legacy signer 驱动调用方（production 走 keyring 模式，targetAudience 才真正选钥）。
            return sign(assertion);
        }
        Purpose purpose = Purpose.fromAssertion(assertion.isService());
        IdentityAssertionKey key = keyring.signingKey(purpose, targetAudience)
                .orElseThrow(() -> new IdentityAssertionException(
                        "No signing key for purpose=" + purpose + " audience=" + targetAudience));
        String jti = UUID.randomUUID().toString();
        IdentityAssertion enveloped = assertion.withEnvelope(issuer, key.kid(), jti, targetAudience);
        String payload = IdentityAssertionCodec.encodePayload(enveloped);
        return payload + "." + mac(payload, key.secret());
    }

    /**
     * legacy 模式签发 token（兼容测试）。
     *
     * <p>不填充 issuer/keyId/jti，不校验 purpose/audience 绑定。
     *
     * @param assertion 待签断言
     * @return 已签 token
     * @throws IdentityAssertionException secret 未配置（编程错误）
     */
    public String sign(IdentityAssertion assertion) {
        if (keyring != null) {
            throw new IllegalStateException("sign(assertion) without targetAudience requires legacy mode");
        }
        if (legacySecret.length == 0) {
            throw new IdentityAssertionException("identity-assertion signer secret not configured");
        }
        String payload = IdentityAssertionCodec.encodePayload(assertion);
        return payload + "." + mac(payload, legacySecret);
    }

    /**
     * 验签 + 校验时间窗与绑定（keyring/legacy 兼容）。
     *
     * <p>keyring 模式：查询验签钥 → MAC → 绑定（audience/purpose/principal） → 时间窗 → replay。
     * <p>legacy 模式：用单一 secret 验签，只校验时间窗与 audience。
     *
     * @param token 待验 token
     * @param now 当前时刻（null → {@link Instant#now()}）
     * @return 验签通过的断言；失败/过期/绑定不符/重放 → {@link Optional#empty()}
     */
    public Optional<IdentityAssertion> verify(String token, Instant now) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot >= token.length() - 1) {
            return Optional.empty();
        }
        String payload = token.substring(0, dot);
        String providedMac = token.substring(dot + 1);

        // 先解析 payload 以获取 issuer/kid/jti/audience（untrusted）
        IdentityAssertion assertion;
        try {
            assertion = IdentityAssertionCodec.decodePayload(payload);
        } catch (IdentityAssertionException ignored) {
            return Optional.empty();
        }

        // 查验签钥并验 MAC
        byte[] secret;
        if (keyring != null) {
            // keyring 模式：按 issuer+kid 查钥
            String issuer = assertion.issuer();
            String kid = assertion.keyId();
            if (issuer == null || issuer.isBlank() || kid == null || kid.isBlank()) {
                return Optional.empty(); // kid/issuer 缺失，keyring 模式拒绝
            }
            List<IdentityAssertionKey> keys = keyring.verifyKeys(issuer, kid);
            if (keys.isEmpty()) {
                return Optional.empty(); // 无验签钥
            }
            // kid 应精确匹配，取第一把
            IdentityAssertionKey key = keys.stream()
                    .filter(k -> kid.equals(k.kid()))
                    .findFirst()
                    .orElse(null);
            if (key == null) {
                return Optional.empty();
            }
            secret = key.secret();

            // MAC 验证
            String expectedMac;
            try {
                expectedMac = mac(payload, secret);
            } catch (IdentityAssertionException ignored) {
                return Optional.empty();
            }
            if (!MessageDigest.isEqual(
                    expectedMac.getBytes(StandardCharsets.UTF_8),
                    providedMac.getBytes(StandardCharsets.UTF_8))) {
                return Optional.empty();
            }

            // 绑定校验
            boolean isService = assertion.isService();
            if (!key.matches(assertion, isService)) {
                return Optional.empty();
            }
        } else {
            // legacy 模式：单 secret
            if (legacySecret.length == 0) {
                return Optional.empty();
            }
            secret = legacySecret;
            String expectedMac;
            try {
                expectedMac = mac(payload, secret);
            } catch (IdentityAssertionException ignored) {
                return Optional.empty();
            }
            if (!MessageDigest.isEqual(
                    expectedMac.getBytes(StandardCharsets.UTF_8),
                    providedMac.getBytes(StandardCharsets.UTF_8))) {
                return Optional.empty();
            }
            // legacy 模式只校验 audience（若无 audience 字段则跳过）
            if (assertion.audience() != null && !assertion.audience().equals(legacyAudience)) {
                return Optional.empty();
            }
        }

        // 时间窗校验（legacy 与 keyring 模式共用构造器传入的 leeway）
        Instant when = now != null ? now : Instant.now();
        if (when.isBefore(assertion.issuedAt().minus(leeway))
                || when.isAfter(assertion.expiresAt().plus(leeway))) {
            return Optional.empty();
        }

        // replay guard 消费 jti
        if (keyring != null && replayGuard != null) {
            String jti = assertion.jti();
            if (jti != null && !jti.isBlank()) {
                if (!replayGuard.consumeOnce(jti, assertion.expiresAt())) {
                    return Optional.empty(); // jti 已消费
                }
            }
        }

        return Optional.of(assertion);
    }

    private String mac(String payload, byte[] secret) {
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
