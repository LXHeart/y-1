package com.grassland.identity.assertion;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 测试辅助工具：为 IT fixtures 提供断言签名能力（GL-P0-ASSERT-001 keyring 模式）。
 *
 * <p>模拟 peer issuer（如 edge-bff）签发用户断言，或模拟服务间断言。
 * 与测试配置中的验签钥 kid/secret 一致。
 */
public final class TestAssertionHelper {

    /** 默认测试密钥长度（32 字节）。 */
    private static final String DEFAULT_SECRET = "test-secret-32-chars-min!!!!!!";

    /** 模拟 edge-bff 签发用户断言（purpose=USER）。 */
    public static IdentityAssertionSigner edgeBffSigner() {
        IdentityAssertionKeyring keyring = keyringFor(
                // edge-bff → identity 的签名钥
                new IdentityAssertionProperties.KeyEntry(
                        "edge-user-identity-v1", "edge-bff", "user", "grassland-identity", DEFAULT_SECRET));
        return new IdentityAssertionSigner(keyring, "edge-bff", AssertionReplayGuard.NO_OP, Duration.ZERO);
    }

    /** 模拟 marketplace 签发服务断言（purpose=SERVICE）。 */
    public static IdentityAssertionSigner marketplaceServiceSigner() {
        IdentityAssertionKeyring keyring = keyringFor(
                new IdentityAssertionProperties.KeyEntry(
                        "marketplace-service-finance-v1", "marketplace", "service", "grassland-finance", DEFAULT_SECRET));
        return new IdentityAssertionSigner(keyring, "marketplace", AssertionReplayGuard.NO_OP, Duration.ZERO);
    }

    /** 模拟 trust 签发服务断言（purpose=SERVICE）。 */
    public static IdentityAssertionSigner trustServiceSigner() {
        IdentityAssertionKeyring keyring = keyringFor(
                new IdentityAssertionProperties.KeyEntry(
                        "trust-service-finance-v1", "trust", "service", "grassland-finance", DEFAULT_SECRET));
        return new IdentityAssertionSigner(keyring, "trust", AssertionReplayGuard.NO_OP, Duration.ZERO);
    }

    /** 构造一个 keyring，仅含指定的签名钥。 */
    private static IdentityAssertionKeyring keyringFor(IdentityAssertionProperties.KeyEntry signingKey) {
        return PropertiesKeyring.from(new IdentityAssertionProperties(
                true, null, 60, null, null, 0, null,
                signingKey.issuer(),
                List.of(signingKey),
                List.of(),
                new IdentityAssertionProperties.ReplayProtectionConfig(false)));
    }

    /** 签一个用户断言（用于模拟 edge-bff 签发）。 */
    public static String signUserAssertion(IdentityAssertionSigner signer, String accountId, String activeIdentityType,
                                           String organizationId, String targetAudience) {
        Instant now = Instant.now();
        IdentityAssertion assertion = new IdentityAssertion(
                accountId, activeIdentityType, "sid-" + accountId, organizationId, null,
                "cookie-session", "level1", null,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "placeholder", now, now.plusSeconds(60),
                "user", null, null);
        return signer.sign(assertion, targetAudience);
    }

    /** 签一个服务断言（用于模拟 marketplace/trust 签发）。 */
    public static String signServiceAssertion(IdentityAssertionSigner signer, String principal, String organizationId,
                                              String targetAudience) {
        Instant now = Instant.now();
        IdentityAssertion assertion = new IdentityAssertion(
                "service:" + principal, null, null, organizationId, null,
                "service", "internal", null,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "placeholder", now, now.plusSeconds(30),
                "service", principal, null);
        return signer.sign(assertion, targetAudience);
    }

    private TestAssertionHelper() {}
}
