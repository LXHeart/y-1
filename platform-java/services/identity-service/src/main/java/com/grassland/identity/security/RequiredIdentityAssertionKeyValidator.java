package com.grassland.identity.security;

import com.grassland.identity.assertion.IdentityAssertionProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Fails startup when Identity cannot verify trusted service assertions used by internal endpoints. */
@Component
@ConditionalOnProperty(prefix = "identity-assertion", name = "enabled", havingValue = "true")
public class RequiredIdentityAssertionKeyValidator {

    public RequiredIdentityAssertionKeyValidator(IdentityAssertionProperties properties) {
        if (!properties.enabled()) {
            return;
        }
        for (String issuer : new String[] {"trust", "marketplace", "intelligence"}) {
            boolean configured = properties.verifyKeys().stream()
                    .anyMatch(key -> issuer.equals(key.issuer())
                            && "service".equalsIgnoreCase(key.purpose())
                            && "grassland-identity".equals(key.audience()));
            if (!configured) {
                String variable = switch (issuer) {
                    case "trust" -> "IDENTITY_ASSERTION_KEY_TRUST_SERVICE_IDENTITY";
                    case "marketplace" -> "IDENTITY_ASSERTION_KEY_MARKETPLACE_SERVICE_IDENTITY";
                    default -> "IDENTITY_ASSERTION_KEY_INTELLIGENCE_SERVICE_IDENTITY";
                };
                throw new IllegalStateException("Missing required " + variable + " configuration");
            }
        }
    }
}
