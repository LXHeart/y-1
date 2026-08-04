package com.grassland.identity.mobile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.identity.assertion.token.AccessToken;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** access token 签发方单测（GL-P3-IDENTITY-001）。 */
class AccessTokenIssuerTest {

    private static final String SECRET = "issuer-test-secret-32-characters!";

    @Test
    void issuedTokenVerifiesWithSameSigner() {
        AccessTokenIssuer issuer = new AccessTokenIssuer(SECRET, "access-token-v1", 900, 5);
        String token = issuer.issue("acc-1", "a@example.com", "user", "device-1", "refresh-1");

        AccessToken decoded = issuer.signer().verify(token, Instant.now()).orElseThrow();
        assertThat(decoded.accountId()).isEqualTo("acc-1");
        assertThat(decoded.email()).isEqualTo("a@example.com");
        assertThat(decoded.role()).isEqualTo("user");
        assertThat(decoded.deviceId()).isEqualTo("device-1");
        assertThat(decoded.sessionToken()).isEqualTo("refresh-1");
        assertThat(decoded.kid()).isEqualTo("access-token-v1");
        assertThat(decoded.exp() - decoded.iat()).isEqualTo(900);
    }

    @Test
    void ttlIsConfigurable() {
        AccessTokenIssuer issuer = new AccessTokenIssuer(SECRET, "access-token-v1", 60, 5);
        assertThat(issuer.ttlSeconds()).isEqualTo(60);
        assertThat(issuer.ttl().toSeconds()).isEqualTo(60);
        String token = issuer.issue("acc-2", "b@example.com", "user", null, "refresh-2");
        AccessToken decoded = issuer.signer().verify(token, Instant.now()).orElseThrow();
        assertThat(decoded.exp() - decoded.iat()).isEqualTo(60);
    }

    @Test
    void unsetSecretIsNotConfiguredAndRefusesToSign() {
        AccessTokenIssuer issuer = new AccessTokenIssuer("", "access-token-v1", 900, 5);
        assertThat(issuer.isConfigured()).isFalse();
        assertThatThrownBy(() -> issuer.issue("acc-3", "c@example.com", "user", null, "refresh-3"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nullSecretBehavesAsUnset() {
        assertThat(new AccessTokenIssuer(null, "access-token-v1", 900, 5).isConfigured()).isFalse();
    }

    @Test
    void tokenFromAnotherSecretDoesNotVerify() {
        AccessTokenIssuer mine = new AccessTokenIssuer(SECRET, "access-token-v1", 900, 5);
        AccessTokenIssuer other = new AccessTokenIssuer("another-secret-32-characters-!!!", "access-token-v1", 900, 5);
        String foreign = other.issue("acc-4", "d@example.com", "user", null, "refresh-4");
        assertThat(mine.signer().verify(foreign, Instant.now())).isEmpty();
    }
}
