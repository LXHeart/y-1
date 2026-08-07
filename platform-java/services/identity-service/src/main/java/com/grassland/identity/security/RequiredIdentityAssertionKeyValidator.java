package com.grassland.identity.security;

import com.grassland.identity.assertion.IdentityAssertionProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Fails startup when Identity cannot verify Trust service assertions used for membership checks. */
@Component
@ConditionalOnProperty(prefix = "identity-assertion", name = "enabled", havingValue = "true")
public class RequiredIdentityAssertionKeyValidator {

    public RequiredIdentityAssertionKeyValidator(IdentityAssertionProperties properties) {
        if (!properties.enabled() || properties.isLegacyMode()) {
            return;
        }
        boolean configured = properties.verifyKeys().stream()
                .anyMatch(key -> "trust".equals(key.issuer())
                        && "service".equalsIgnoreCase(key.purpose())
                        && "grassland-identity".equals(key.audience()));
        if (!configured) {
            throw new IllegalStateException(
                    "Missing required IDENTITY_ASSERTION_KEY_TRUST_SERVICE_IDENTITY configuration");
        }
    }
}
