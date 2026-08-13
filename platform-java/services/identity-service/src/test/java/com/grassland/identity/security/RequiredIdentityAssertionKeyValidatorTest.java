package com.grassland.identity.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.identity.assertion.IdentityAssertionProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequiredIdentityAssertionKeyValidatorTest {

    @Test
    void rejectsKeyringWithoutTrustToIdentityVerificationKey() {
        IdentityAssertionProperties props = properties(List.of(
                key("edge", "edge-bff", "user", "grassland-identity")));

        assertThatThrownBy(() -> new RequiredIdentityAssertionKeyValidator(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IDENTITY_ASSERTION_KEY_TRUST_SERVICE_IDENTITY");
    }

    @Test
    void acceptsAllTrustedServiceKeys() {
        assertThatCode(() -> new RequiredIdentityAssertionKeyValidator(properties(List.of(
                key("trust", "trust", "service", "grassland-identity"),
                key("marketplace", "marketplace", "service", "grassland-identity"),
                key("intelligence", "intelligence", "service", "grassland-identity")))))
                .doesNotThrowAnyException();
    }

    private static IdentityAssertionProperties properties(List<IdentityAssertionProperties.KeyEntry> verify) {
        return new IdentityAssertionProperties(true, 60, null, null, 5, null,
                "identity", List.of(), verify, null);
    }

    private static IdentityAssertionProperties.KeyEntry key(
            String kid, String issuer, String purpose, String audience) {
        return new IdentityAssertionProperties.KeyEntry(kid, issuer, purpose, audience, "secret");
    }
}
