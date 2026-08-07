package com.grassland.intelligence.security;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Issues short-lived intelligence service assertions for direct domain-service calls. */
@Component
public final class IntelligenceServiceAssertionIssuer {

    public static final String PRINCIPAL = "intelligence";
    private static final long TTL_SECONDS = 30;

    private final IdentityAssertionSigner signer;

    public IntelligenceServiceAssertionIssuer(IdentityAssertionSigner signer) {
        this.signer = signer;
    }

    public String issueService(String targetAudience) {
        Instant now = Instant.now();
        IdentityAssertion assertion = new IdentityAssertion(
                "service:" + PRINCIPAL, null, null, null, null,
                "service", "internal", null,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                targetAudience, now, now.plusSeconds(TTL_SECONDS),
                "service", PRINCIPAL, null);
        return signer.sign(assertion, targetAudience);
    }
}
