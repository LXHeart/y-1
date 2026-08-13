package com.grassland.trust.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.identity.assertion.IdentityAssertionProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequiredIdentityAssertionKeyValidatorTest {

    @Test
    void rejectsKeyringWithoutTrustToIdentitySigningKey() {
        IdentityAssertionProperties props = properties(List.of(key("finance", "grassland-finance")));

        assertThatThrownBy(() -> new RequiredIdentityAssertionKeyValidator(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IDENTITY_ASSERTION_KEY_TRUST_SERVICE_IDENTITY");
    }

    @Test
    void rejectsNonTrustIssuerEvenWhenTheRequiredKeyExists() {
        IdentityAssertionProperties props = new IdentityAssertionProperties(
                true, 60, null, null, 5, null,
                "marketplace", List.of(key("identity", "grassland-identity")), List.of(), null);

        assertThatThrownBy(() -> new RequiredIdentityAssertionKeyValidator(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IDENTITY_ASSERTION_ISSUER");
    }

    @Test
    void acceptsTrustToIdentitySigningKey() {
        assertThatCode(() -> new RequiredIdentityAssertionKeyValidator(
                properties(List.of(key("identity", "grassland-identity")))))
                .doesNotThrowAnyException();
    }

    private static IdentityAssertionProperties properties(List<IdentityAssertionProperties.KeyEntry> signing) {
        return new IdentityAssertionProperties(true, 60, null, null, 5, null,
                "trust", signing, List.of(), null);
    }

    private static IdentityAssertionProperties.KeyEntry key(String kid, String audience) {
        return new IdentityAssertionProperties.KeyEntry(kid, null, "service", audience, "secret");
    }
}
