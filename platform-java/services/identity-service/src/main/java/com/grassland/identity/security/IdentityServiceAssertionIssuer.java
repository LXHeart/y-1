package com.grassland.identity.security;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import com.grassland.identity.auth.IdentityException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 为 identity 主动调用其他领域服务签发短时服务断言。 */
@Component
public class IdentityServiceAssertionIssuer {

    public static final String PRINCIPAL = "identity";
    private static final long TTL_SECONDS = 30;

    private final ObjectProvider<IdentityAssertionSigner> signers;

    public IdentityServiceAssertionIssuer(ObjectProvider<IdentityAssertionSigner> signers) {
        this.signers = signers;
    }

    public String issueForOrganization(String organizationId, String targetAudience) {
        IdentityAssertionSigner signer = signers.getIfAvailable();
        if (signer == null) {
            throw new IdentityException(503, "媒体校验服务暂不可用");
        }
        Instant now = Instant.now();
        IdentityAssertion assertion = new IdentityAssertion(
                "service:" + PRINCIPAL, null, null, organizationId, null,
                "service", "internal", null,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                targetAudience, now, now.plusSeconds(TTL_SECONDS),
                "service", PRINCIPAL, null);
        try {
            return signer.sign(assertion, targetAudience);
        } catch (RuntimeException error) {
            throw new IdentityException(503, "媒体校验服务暂不可用");
        }
    }
}
