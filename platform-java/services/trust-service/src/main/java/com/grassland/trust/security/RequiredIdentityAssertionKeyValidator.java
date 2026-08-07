package com.grassland.trust.security;

import com.grassland.identity.assertion.IdentityAssertionProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Fails startup when Trust cannot sign the service assertion required by Identity membership checks. */
@Component
@ConditionalOnProperty(prefix = "identity-assertion", name = "enabled", havingValue = "true")
public class RequiredIdentityAssertionKeyValidator {

    public RequiredIdentityAssertionKeyValidator(IdentityAssertionProperties properties) {
        if (!properties.enabled() || properties.isLegacyMode()) {
            return;
        }
        if (!"trust".equals(properties.issuer())) {
            throw new IllegalStateException("Invalid IDENTITY_ASSERTION_ISSUER for Trust service");
        }
        boolean configured = properties.signingKeys().stream()
                .anyMatch(key -> "service".equalsIgnoreCase(key.purpose())
                        && "grassland-identity".equals(key.audience()));
        if (!configured) {
            throw new IllegalStateException(
                    "Missing required IDENTITY_ASSERTION_KEY_TRUST_SERVICE_IDENTITY configuration");
        }
    }
}
