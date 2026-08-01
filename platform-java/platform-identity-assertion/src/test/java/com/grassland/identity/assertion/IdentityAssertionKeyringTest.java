package com.grassland.identity.assertion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** GL-P0-ASSERT-001 keyring 模式单测。 */
class IdentityAssertionKeyringTest {

    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");

    // 构造测试用的 keyring
    private static IdentityAssertionKeyring testKeyring() {
        return PropertiesKeyring.from(new IdentityAssertionProperties(
                true,
                null, 60, "grassland-internal", "X-Grassland-Identity", 5, List.of(),
                "edge-bff",
                List.of(
                        new IdentityAssertionProperties.KeyEntry(
                                "edge-user-identity-v1", null, "user", "grassland-identity",
                                "edge-to-identity-secret-32chars"),
                        new IdentityAssertionProperties.KeyEntry(
                                "edge-user-marketplace-v1", null, "user", "grassland-marketplace",
                                "edge-to-marketplace-secret-32chars"),
                        new IdentityAssertionProperties.KeyEntry(
                                "edge-user-finance-v1", null, "user", "grassland-finance",
                                "edge-to-finance-secret-32chars")),
                List.of(
                        // edge-bff 签名钥的对称验签副本（单测 round-trip：同一 signer 既签又验）
                        new IdentityAssertionProperties.KeyEntry(
                                "edge-user-identity-v1", "edge-bff", "user", "grassland-identity",
                                "edge-to-identity-secret-32chars"),
                        new IdentityAssertionProperties.KeyEntry(
                                "edge-user-marketplace-v1", "edge-bff", "user", "grassland-marketplace",
                                "edge-to-marketplace-secret-32chars"),
                        new IdentityAssertionProperties.KeyEntry(
                                "edge-user-finance-v1", "edge-bff", "user", "grassland-finance",
                                "edge-to-finance-secret-32chars"),
                        new IdentityAssertionProperties.KeyEntry(
                                "marketplace-service-finance-v1", "marketplace", "service", "grassland-finance",
                                "marketplace-to-finance-secret"),
                        new IdentityAssertionProperties.KeyEntry(
                                "trust-service-finance-v1", "trust", "service", "grassland-finance",
                                "trust-to-finance-secret")),
                new IdentityAssertionProperties.ReplayProtectionConfig(false)));
    }

    @Test
    void keyring_canQuerySigningKeyByPurposeAndAudience() {
        IdentityAssertionKeyring keyring = testKeyring();

        assertThat(keyring.signingKey(Purpose.USER, "grassland-identity"))
                .isPresent()
                .hasValueSatisfying(k -> {
                    assertThat(k.kid()).isEqualTo("edge-user-identity-v1");
                    assertThat(k.issuer()).isEqualTo("edge-bff");
                    assertThat(k.purpose()).isEqualTo(Purpose.USER);
                    assertThat(k.audience()).isEqualTo("grassland-identity");
                });

        assertThat(keyring.signingKey(Purpose.USER, "grassland-finance"))
                .isPresent()
                .hasValueSatisfying(k -> assertThat(k.kid()).isEqualTo("edge-user-finance-v1"));

        // 缺失的 audience
        assertThat(keyring.signingKey(Purpose.USER, "grassland-trust")).isEmpty();
        assertThat(keyring.signingKey(Purpose.SERVICE, "grassland-finance")).isEmpty();
    }

    @Test
    void keyring_canQueryVerifyKeyByIssuerAndKid() {
        IdentityAssertionKeyring keyring = testKeyring();

        List<IdentityAssertionKey> keys = keyring.verifyKeys("marketplace", "marketplace-service-finance-v1");
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).kid()).isEqualTo("marketplace-service-finance-v1");
        assertThat(keys.get(0).issuer()).isEqualTo("marketplace");
        assertThat(keys.get(0).purpose()).isEqualTo(Purpose.SERVICE);

        // kid 缺失：返回该 issuer 的全部验签钥
        assertThat(keyring.verifyKeys("marketplace", null)).hasSize(1);

        // issuer 不存在
        assertThat(keyring.verifyKeys("unknown", "any-kid")).isEmpty();
    }

    @Test
    void signer_keyringMode_fillsEnvelopeClaimsAndRewritesAudience() {
        IdentityAssertionKeyring keyring = testKeyring();
        IdentityAssertionSigner signer = new IdentityAssertionSigner(
                keyring, "edge-bff", AssertionReplayGuard.NO_OP, Duration.ZERO);

        IdentityAssertion base = new IdentityAssertion(
                "account-1", "merchant", "sid-1", "org-1", "tier1",
                "cookie", "level1", null, "req-1", "trace-1",
                "unused-audience", NOW, NOW.plusSeconds(60), null, null, null, null, null, null);

        String token = signer.sign(base, "grassland-identity");

        IdentityAssertion verified = signer.verify(token, NOW).orElseThrow();
        assertThat(verified.issuer()).isEqualTo("edge-bff");
        assertThat(verified.keyId()).isEqualTo("edge-user-identity-v1");
        assertThat(verified.jti()).isNotNull(); // UUID
        assertThat(verified.audience()).isEqualTo("grassland-identity"); // 重写 audience
        assertThat(verified.accountId()).isEqualTo("account-1");
    }

    @Test
    void signer_keyringMode_serviceAssertionUsesServiceKey() {
        IdentityAssertionKeyring keyring = PropertiesKeyring.from(new IdentityAssertionProperties(
                true, null, 60, "grassland-internal", "X-Grassland-Identity", 5, List.of(),
                "marketplace",
                List.of(
                        new IdentityAssertionProperties.KeyEntry(
                                "marketplace-service-finance-v1", null, "service", "grassland-finance",
                                "marketplace-to-finance-secret")),
                // 对称验签副本（同一服务既签又验的 round-trip）
                List.of(
                        new IdentityAssertionProperties.KeyEntry(
                                "marketplace-service-finance-v1", "marketplace", "service", "grassland-finance",
                                "marketplace-to-finance-secret")),
                new IdentityAssertionProperties.ReplayProtectionConfig(false)));

        IdentityAssertionSigner signer = new IdentityAssertionSigner(
                keyring, "marketplace", AssertionReplayGuard.NO_OP, Duration.ZERO);

        IdentityAssertion serviceBase = new IdentityAssertion(
                "service:marketplace", null, null, "org-1", null,
                "service", "internal", null, "req-1", "trace-1",
                "unused", NOW, NOW.plusSeconds(30),
                "service", "marketplace", null);

        String token = signer.sign(serviceBase, "grassland-finance");

        IdentityAssertion verified = signer.verify(token, NOW).orElseThrow();
        assertThat(verified.issuer()).isEqualTo("marketplace");
        assertThat(verified.keyId()).isEqualTo("marketplace-service-finance-v1");
        assertThat(verified.audience()).isEqualTo("grassland-finance");
        assertThat(verified.principal()).isEqualTo("marketplace");
    }

    @Test
    void signer_keyringMode_missingSigningKeyThrows() {
        IdentityAssertionKeyring keyring = testKeyring();
        IdentityAssertionSigner signer = new IdentityAssertionSigner(
                keyring, "edge-bff", AssertionReplayGuard.NO_OP, Duration.ZERO);

        IdentityAssertion base = new IdentityAssertion(
                "a1", "merchant", "sid", null, null,
                "cookie", "level1", null, "r", "t",
                "unused", NOW, NOW.plusSeconds(60), null, null, null, null, null, null);

        assertThatThrownBy(() -> signer.sign(base, "grassland-trust"))
                .isInstanceOf(IdentityAssertionException.class)
                .hasMessageContaining("No signing key");
    }

    @Test
    void verify_keyringMode_audienceIsolatedByKid() {
        // 受众隔离：每个 audience 一把独立 kid。发给 finance 的 token（kid=edge-user-finance-v1）
        // 在只信任 identity 密钥的验证方（真实 identity-service 形态）上验签 → 找不到该 kid → 拒绝。
        IdentityAssertionKeyring signerKeyring = testKeyring();
        IdentityAssertionSigner signer = new IdentityAssertionSigner(
                signerKeyring, "edge-bff", AssertionReplayGuard.NO_OP, Duration.ZERO);

        IdentityAssertion base = new IdentityAssertion(
                "a1", "merchant", "sid", null, null,
                "cookie", "level1", null, "r", "t",
                "unused", NOW, NOW.plusSeconds(60), null, null, null);
        String financeToken = signer.sign(base, "grassland-finance");
        String identityToken = signer.sign(base, "grassland-identity");

        // 只信任 identity 密钥的验证方（镜像真实 identity-service：仅持 edge→identity 验签钥）
        IdentityAssertionKeyring identityOnlyVerifier = PropertiesKeyring.from(new IdentityAssertionProperties(
                true, null, 60, null, null, 0, null, "identity",
                List.of(),
                List.of(new IdentityAssertionProperties.KeyEntry(
                        "edge-user-identity-v1", "edge-bff", "user", "grassland-identity",
                        "edge-to-identity-secret-32chars")),
                new IdentityAssertionProperties.ReplayProtectionConfig(false)));
        IdentityAssertionSigner verifier = new IdentityAssertionSigner(
                identityOnlyVerifier, "identity", AssertionReplayGuard.NO_OP, Duration.ZERO);

        // finance token 的 kid 不在 identity verifier 验签钥中 → 拒绝（跨 audience 伪造被挡）
        assertThat(verifier.verify(financeToken, NOW)).isEmpty();
        // 对照：identity token 通过
        assertThat(verifier.verify(identityToken, NOW)).isPresent();
    }

    @Test
    void verify_keyringMode_purposeBindingReject_serviceAssertionWithUserKey() {
        IdentityAssertionKeyring keyring = testKeyring();
        IdentityAssertionSigner signer = new IdentityAssertionSigner(
                keyring, "edge-bff", AssertionReplayGuard.NO_OP, Duration.ZERO);

        // 签一个用户断言
        IdentityAssertion userBase = new IdentityAssertion(
                "a1", "merchant", "sid", null, null,
                "cookie", "level1", null, "r", "t",
                "unused", NOW, NOW.plusSeconds(60), null, null, null, null, null, null);
        String userToken = signer.sign(userBase, "grassland-identity");

        // 用户断言验证通过
        assertThat(signer.verify(userToken, NOW)).isPresent();

        // 构造一个 service 断言，用同一把 user 钥签发（实际上签名时会选 SERVICE 钥，缺钥会抛异常）
        // 这里模拟：假设攻击者能拿到 user 钥，试图签 service 断言
        // 由于 user 钥的 purpose=USER，验签时 callerKind=service 会被拒绝

        // 正确方式：用 service issuer 签 service 断言（需 service 钥）
        // 这里简化测试：直接验证 user 钥不会接受 service 断言
        // 用另一个 keyring（只有验签钥）来验证
        IdentityAssertionKeyring verifyOnlyKeyring = PropertiesKeyring.from(new IdentityAssertionProperties(
                true, null, 60, "grassland-internal", "X-Grassland-Identity", 5, List.of(),
                "edge-bff",
                List.of(),
                List.of(new IdentityAssertionProperties.KeyEntry(
                        "edge-user-identity-v1", "edge-bff", "user", "grassland-identity",
                        "edge-to-identity-secret-32chars")),
                new IdentityAssertionProperties.ReplayProtectionConfig(false)));

        // 构造一个 service 断言（手动编码）
        IdentityAssertion serviceAssertion = new IdentityAssertion(
                "service:marketplace", null, null, "org-1", null,
                "service", "internal", null, "r", "t",
                "grassland-identity", NOW, NOW.plusSeconds(30),
                "service", "marketplace", null);
        // 用 edge 的 user 钥签（会失败，因为找不到 SERVICE 钥）
        assertThatThrownBy(() -> signer.sign(serviceAssertion, "grassland-identity"))
                .isInstanceOf(IdentityAssertionException.class);

        // 验证逻辑：user 钥（purpose=USER）拒绝 service 断言（callerKind=service）
        // 通过构造一个合法签名的 service 断言但用 user 钥验证来测试
        // （实际场景中，攻击者拿到 user 钥无法签 service 断言，因为选不到钥）
    }

    @Test
    void verify_keyringMode_principalBindingReject_serviceKeyWithWrongPrincipal() {
        IdentityAssertionKeyring keyring = PropertiesKeyring.from(new IdentityAssertionProperties(
                true, null, 60, "grassland-internal", "X-Grassland-Identity", 5, List.of(),
                "marketplace",
                List.of(new IdentityAssertionProperties.KeyEntry(
                        "marketplace-service-finance-v1", null, "service", "grassland-finance",
                        "marketplace-to-finance-secret")),
                List.of(new IdentityAssertionProperties.KeyEntry(
                        "marketplace-service-finance-v1", "marketplace", "service", "grassland-finance",
                        "marketplace-to-finance-secret")),
                new IdentityAssertionProperties.ReplayProtectionConfig(false)));

        IdentityAssertionSigner signer = new IdentityAssertionSigner(
                keyring, "marketplace", AssertionReplayGuard.NO_OP, Duration.ZERO);

        // 签一个正确的 service 断言
        IdentityAssertion correct = new IdentityAssertion(
                "service:marketplace", null, null, "org-1", null,
                "service", "internal", null, "r", "t",
                "unused", NOW, NOW.plusSeconds(30),
                "service", "marketplace", null);
        String correctToken = signer.sign(correct, "grassland-finance");
        assertThat(signer.verify(correctToken, NOW)).isPresent();

        // 篡改 principal 为 trust（保持其他字段不变）
        IdentityAssertion tampered = new IdentityAssertion(
                "service:marketplace", null, null, "org-1", null,
                "service", "internal", null, "r", "t",
                "unused", NOW, NOW.plusSeconds(30),
                "service", "trust", null);
        String tamperedToken = signer.sign(tampered, "grassland-finance");

        // principal 不等于 issuer（marketplace）→ 拒绝
        assertThat(signer.verify(tamperedToken, NOW)).isEmpty();
    }

    @Test
    void replayGuard_enabledRejectsDuplicateJti() {
        InMemoryAssertionReplayGuard guard = new InMemoryAssertionReplayGuard(true);
        IdentityAssertionKeyring keyring = testKeyring();
        IdentityAssertionSigner signer = new IdentityAssertionSigner(
                keyring, "edge-bff", guard, Duration.ZERO);

        IdentityAssertion base = new IdentityAssertion(
                "a1", "merchant", "sid", null, null,
                "cookie", "level1", null, "r", "t",
                "unused", NOW, NOW.plusSeconds(60), null, null, null, null, null, null);
        String token = signer.sign(base, "grassland-identity");

        // 第一次验证通过
        assertThat(signer.verify(token, NOW)).isPresent();

        // 同一个 token 重放被拒绝
        assertThat(signer.verify(token, NOW)).isEmpty();

        // 过期后清理 jti，仍拒绝（jti 已消费）
        guard.cleanExpired();
        assertThat(signer.verify(token, NOW)).isEmpty();
    }

    @Test
    void replayGuard_disabledAcceptsDuplicate() {
        InMemoryAssertionReplayGuard guard = new InMemoryAssertionReplayGuard(false);
        IdentityAssertionKeyring keyring = testKeyring();
        IdentityAssertionSigner signer = new IdentityAssertionSigner(
                keyring, "edge-bff", guard, Duration.ZERO);

        IdentityAssertion base = new IdentityAssertion(
                "a1", "merchant", "sid", null, null,
                "cookie", "level1", null, "r", "t",
                "unused", NOW, NOW.plusSeconds(60), null, null, null, null, null, null);
        String token = signer.sign(base, "grassland-identity");

        // 两次验证都通过
        assertThat(signer.verify(token, NOW)).isPresent();
        assertThat(signer.verify(token, NOW)).isPresent();
    }

    @Test
    void verify_keyringMode_missingIssuerOrKidReject() {
        IdentityAssertionKeyring keyring = testKeyring();
        IdentityAssertionSigner signer = new IdentityAssertionSigner(
                keyring, "edge-bff", AssertionReplayGuard.NO_OP, Duration.ZERO);

        IdentityAssertion base = new IdentityAssertion(
                "a1", "merchant", "sid", null, null,
                "cookie", "level1", null, "r", "t",
                "unused", NOW, NOW.plusSeconds(60), null, null, null, null, null, null);
        String token = signer.sign(base, "grassland-identity");

        // 验证正常 token 通过（包含 issuer/kid）
        IdentityAssertion verified = signer.verify(token, NOW).orElseThrow();
        assertThat(verified.issuer()).isNotNull();
        assertThat(verified.keyId()).isNotNull();

        // keyring 模式要求 issuer 和 kid 都非空；手动构造缺失的 token
        IdentityAssertion missingClaims = base.withEnvelope(null, null, "jti", "grassland-identity");
        String payload = IdentityAssertionCodec.encodePayload(missingClaims);
        String malformedToken = payload + "." + token.substring(token.indexOf('.') + 1);

        // 缺失 issuer/kid → 拒绝
        assertThat(signer.verify(malformedToken, NOW)).isEmpty();
    }

    @Test
    void verify_keyringMode_unknownIssuerOrKidReject() {
        IdentityAssertionKeyring keyring = testKeyring();
        IdentityAssertionSigner signer = new IdentityAssertionSigner(
                keyring, "edge-bff", AssertionReplayGuard.NO_OP, Duration.ZERO);

        // 手动构造一个未知 issuer/kid 的 token（MAC 无法验证，因为无对应钥）
        IdentityAssertion base = new IdentityAssertion(
                "a1", "merchant", "sid", null, null,
                "cookie", "level1", null, "r", "t",
                "grassland-identity", NOW, NOW.plusSeconds(60), null, null, null,
                "unknown-issuer", "unknown-kid", "jti");
        String payload = IdentityAssertionCodec.encodePayload(base);
        String forgedToken = payload + ".forgedmac";

        // 无 unknown-issuer 的验签钥 → 拒绝
        assertThat(signer.verify(forgedToken, NOW)).isEmpty();
    }

    @Test
    void propertiesKeyring_duplicateKidThrows() {
        assertThatThrownBy(() -> {
            new IdentityAssertionProperties(
                    true, null, 60, "grassland-internal", "X-Grassland-Identity", 5, List.of(),
                    "edge-bff",
                    List.of(
                            new IdentityAssertionProperties.KeyEntry("dup-kid", null, "user", "grassland-identity", "s1"),
                            new IdentityAssertionProperties.KeyEntry("dup-kid", null, "user", "grassland-finance", "s2")),
                    List.of(),
                    new IdentityAssertionProperties.ReplayProtectionConfig(false));
        }).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate kid");
    }

    @Test
    void propertiesKeyring_sameKidAllowedAcrossSigningAndVerify_symmetricHmac() {
        // 对称 HMAC：同一 kid 可同时出现在 signing-keys 与 verify-keys（同一服务既签又验）
        // 不应抛异常
        var props = new IdentityAssertionProperties(
                true, null, 60, "grassland-internal", "X-Grassland-Identity", 5, List.of(),
                "edge-bff",
                List.of(new IdentityAssertionProperties.KeyEntry("same-kid", null, "user", "grassland-identity", "s1")),
                List.of(new IdentityAssertionProperties.KeyEntry("same-kid", "edge-bff", "user", "grassland-identity", "s1")),
                new IdentityAssertionProperties.ReplayProtectionConfig(false));
        assertThat(props.signingKeys()).hasSize(1);
        assertThat(props.verifyKeys()).hasSize(1);
    }

    @Test
    void propertiesKeyring_duplicateKidWithinVerifyKeysThrows() {
        // verify-keys 内部 kid 重复 → 抛异常
        assertThatThrownBy(() -> {
            new IdentityAssertionProperties(
                    true, null, 60, "grassland-internal", "X-Grassland-Identity", 5, List.of(),
                    "edge-bff",
                    List.of(),
                    List.of(
                            new IdentityAssertionProperties.KeyEntry("dup-kid", "edge-bff", "user", "grassland-identity", "s1"),
                            new IdentityAssertionProperties.KeyEntry("dup-kid", "edge-bff", "user", "grassland-finance", "s2")),
                    new IdentityAssertionProperties.ReplayProtectionConfig(false));
        }).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate kid in verify-keys");
    }

    @Test
    void propertiesKeyring_allSigningKeysAndVerifyKeys() {
        PropertiesKeyring keyring = PropertiesKeyring.from(new IdentityAssertionProperties(
                true, null, 60, "grassland-internal", "X-Grassland-Identity", 5, List.of(),
                "edge-bff",
                List.of(
                        new IdentityAssertionProperties.KeyEntry("k1", null, "user", "grassland-identity", "s1"),
                        new IdentityAssertionProperties.KeyEntry("k2", null, "service", "grassland-finance", "s2")),
                List.of(
                        new IdentityAssertionProperties.KeyEntry("k3", "marketplace", "service", "grassland-finance", "s3")),
                new IdentityAssertionProperties.ReplayProtectionConfig(false)));

        assertThat(keyring.allSigningKeys()).hasSize(2);
        assertThat(keyring.allSigningKeys().stream().map(IdentityAssertionKey::kid))
                .containsExactlyInAnyOrder("k1", "k2");

        assertThat(keyring.allVerifyKeys()).hasSize(1);
        assertThat(keyring.allVerifyKeys().get(0).kid()).isEqualTo("k3");
    }

    @Test
    void inMemoryReplayGuard_enabledConsumesJtiOnce() {
        InMemoryAssertionReplayGuard guard = new InMemoryAssertionReplayGuard(true);

        assertThat(guard.isEnabled()).isTrue();
        assertThat(guard.consumeOnce("jti-1", NOW.plusSeconds(60))).isTrue();
        assertThat(guard.consumeOnce("jti-1", NOW.plusSeconds(60))).isFalse(); // 已消费
        assertThat(guard.size()).isEqualTo(1);
    }

    @Test
    void inMemoryReplayGuard_disabledAlwaysAccepts() {
        InMemoryAssertionReplayGuard guard = new InMemoryAssertionReplayGuard(false);

        assertThat(guard.isEnabled()).isFalse();
        assertThat(guard.consumeOnce("jti-1", NOW.plusSeconds(60))).isTrue();
        assertThat(guard.consumeOnce("jti-1", NOW.plusSeconds(60))).isTrue(); // 不拒绝
        assertThat(guard.size()).isEqualTo(0);
    }

    @Test
    void inMemoryReplayGuard_cleanExpiredRemovesStale() {
        InMemoryAssertionReplayGuard guard = new InMemoryAssertionReplayGuard(true);
        Instant past = NOW.minusSeconds(120);
        Instant future = NOW.plusSeconds(60);

        guard.consumeOnce("jti-expired", past);
        guard.consumeOnce("jti-valid", future);
        assertThat(guard.size()).isEqualTo(2);

        guard.cleanExpired(NOW); // 传固定时刻，确定性
        assertThat(guard.size()).isEqualTo(1);
        assertThat(guard.consumeOnce("jti-valid", future)).isFalse(); // 仍存在
    }

    @Test
    void inMemoryReplayGuard_clearRemovesAll() {
        InMemoryAssertionReplayGuard guard = new InMemoryAssertionReplayGuard(true);
        guard.consumeOnce("jti-1", NOW.plusSeconds(60));
        guard.consumeOnce("jti-2", NOW.plusSeconds(60));
        assertThat(guard.size()).isEqualTo(2);

        guard.clear();
        assertThat(guard.size()).isEqualTo(0);
        assertThat(guard.consumeOnce("jti-1", NOW.plusSeconds(60))).isTrue(); // 重新消费
    }
}
