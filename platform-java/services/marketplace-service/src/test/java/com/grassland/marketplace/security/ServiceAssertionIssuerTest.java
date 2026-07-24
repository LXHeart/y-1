package com.grassland.marketplace.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * {@link ServiceAssertionIssuer} 签发的服务断言可被 finance 端 {@link IdentityAssertionSigner#verify} 验签通过，
 * 且携带 callerKind=service / principal=marketplace / org 上下文（HLD 11.1，Slice 4F）。
 */
class ServiceAssertionIssuerTest {

    private static final String SECRET = "test-secret-32-chars-min!!!";
    private static final String AUDIENCE = "grassland-internal";
    private static final String ORG = "11111111-1111-1111-1111-111111111111";

    private final IdentityAssertionSigner signer =
            new IdentityAssertionSigner(SECRET.getBytes(), AUDIENCE, Duration.ofSeconds(5));

    @Test
    void issuedTokenVerifiesAsMarketplaceService() {
        ServiceAssertionIssuer issuer = new ServiceAssertionIssuer(signer, AUDIENCE);

        IdentityAssertion verified = signer.verify(issuer.issueForOrg(ORG), Instant.now()).orElseThrow();

        assertThat(verified.isService()).isTrue();
        assertThat(verified.principal()).isEqualTo("marketplace");
        assertThat(verified.callerKind()).isEqualTo("service");
        assertThat(verified.organizationId()).isEqualTo(ORG);
        assertThat(verified.audience()).isEqualTo(AUDIENCE);
    }

    @Test
    void issuedTokenCannotImpersonateMerchant() {
        ServiceAssertionIssuer issuer = new ServiceAssertionIssuer(signer, AUDIENCE);

        IdentityAssertion verified = signer.verify(issuer.issueForOrg(ORG), Instant.now()).orElseThrow();

        // 服务断言 activeIdentityType 为 null——finance 端 isMerchant() 对 service callerKind 恒为 false
        assertThat(verified.activeIdentityType()).isNull();
        assertThat(verified.isUser()).isFalse();
    }

    @Test
    void eachCallMintsFreshToken() {
        ServiceAssertionIssuer issuer = new ServiceAssertionIssuer(signer, AUDIENCE);

        String a = issuer.issueForOrg(ORG);
        String b = issuer.issueForOrg(ORG);

        // requestId/traceId 每次 UUID，故 payload 不同 → token 不同（规避重放/固定 TTL 过期）
        assertThat(a).isNotEqualTo(b);
        assertThat(signer.verify(a, Instant.now())).isPresent();
        assertThat(signer.verify(b, Instant.now())).isPresent();
    }
}
