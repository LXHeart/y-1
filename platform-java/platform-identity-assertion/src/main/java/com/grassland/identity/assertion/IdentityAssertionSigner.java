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
import reactor.core.publisher.Mono;

/**
 * 内部身份断言的 HMAC-SHA256 签发/验签（GL-P0-ASSERT-001 keyring 模式）。
 *
 * <h3>Token 格式</h3>
 * {@code <payloadB64url>.<macB64url>}，MAC = base64url(HMAC-SHA256(secret, payloadB64url))。
 *
 * <p>仅支持 keyring 构造器：按 {@code (purpose, targetAudience)} 选签名钥，
 * 并在验签时按 {@code (issuer, kid)} 校验 audience/purpose/principal 绑定。</p>
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
        Purpose purpose = Purpose.fromAssertion(assertion.isService());
        IdentityAssertionKey key = keyring.signingKey(purpose, targetAudience)
                .orElseThrow(() -> new IdentityAssertionException(
                        "No signing key for purpose=" + purpose + " audience=" + targetAudience));
        String jti = UUID.randomUUID().toString();
        IdentityAssertion enveloped = assertion.withEnvelope(issuer, key.kid(), jti, targetAudience);
        String payload = IdentityAssertionCodec.encodePayload(enveloped);
        return payload + "." + mac(payload, key.secret());
    }

    /** Keyring convenience for fixtures whose assertion already carries its target audience. */
    public String sign(IdentityAssertion assertion) {
        String targetAudience = assertion.audience();
        if (targetAudience == null || targetAudience.isBlank()) {
            throw new IdentityAssertionException("assertion audience must be set when signing without targetAudience");
        }
        return sign(assertion, targetAudience);
    }

    /**
     * 验签 + 校验时间窗与绑定。
     *
     * <p>查询验签钥 → MAC → 绑定（audience/purpose/principal） → 时间窗 → replay。
     *
     * @param token 待验 token
     * @param now 当前时刻（null → {@link Instant#now()}）
     * @return 验签通过的断言；失败/过期/绑定不符/重放 → {@link Optional#empty()}
     */
    public Optional<IdentityAssertion> verify(String token, Instant now) {
        Optional<IdentityAssertion> verified = verifyWithoutReplay(token, now);
        if (verified.isEmpty() || replayGuard == null) {
            return verified;
        }
        IdentityAssertion assertion = verified.get();
        String jti = assertion.jti();
        if (jti != null && !jti.isBlank()
                && !replayGuard.consumeOnce(jti, assertion.expiresAt().plus(leeway))) {
            return Optional.empty();
        }
        return verified;
    }

    /**
     * Reactive 验签入口。生产 WebFlux 请求链应使用本方法，使 Redis replay 消费保持非阻塞。
     * 密码学与 claim 校验在内存中同步完成，只有 replay guard 访问共享存储。
     */
    public Mono<IdentityAssertion> verifyReactive(String token, Instant now) {
        Optional<IdentityAssertion> verified = verifyWithoutReplay(token, now);
        if (verified.isEmpty()) {
            return Mono.empty();
        }
        IdentityAssertion assertion = verified.get();
        if (replayGuard == null || assertion.jti() == null || assertion.jti().isBlank()) {
            return Mono.just(assertion);
        }
        return replayGuard.consumeOnceReactive(assertion.jti(), assertion.expiresAt().plus(leeway))
                .filter(Boolean.TRUE::equals)
                .map(ignored -> assertion);
    }

    private Optional<IdentityAssertion> verifyWithoutReplay(String token, Instant now) {
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
        {
            // 按 issuer+kid 查钥
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
        }

        // 时间窗校验
        Instant when = now != null ? now : Instant.now();
        if (when.isBefore(assertion.issuedAt().minus(leeway))
                || when.isAfter(assertion.expiresAt().plus(leeway))) {
            return Optional.empty();
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
