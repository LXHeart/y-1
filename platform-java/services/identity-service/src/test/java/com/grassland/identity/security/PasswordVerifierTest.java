package com.grassland.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordVerifierTest {
    private final PasswordVerifier verifier = new PasswordVerifier();

    @Test
    void roundTripsBcryptHash() {
        String hash = at.favre.lib.crypto.bcrypt.BCrypt.withDefaults()
            .hashToString(12, "correct horse battery".toCharArray());
        assertThat(verifier.verify("correct horse battery", hash)).isTrue();
        assertThat(verifier.verify("wrong password", hash)).isFalse();
    }

    @Test
    void rejectsInvalidInputs() {
        assertThat(verifier.verify(null, "$2a$12$abc")).isFalse();
        assertThat(verifier.verify("pw", null)).isFalse();
        assertThat(verifier.verify("pw", "")).isFalse();
        assertThat(verifier.verify("pw", "not-a-hash")).isFalse();
    }

    @Test
    void argon2RoundTrips() {
        Argon2PasswordHasher hasher = new Argon2PasswordHasher();
        String hash = hasher.hash("S3cret-pass");
        assertThat(hasher.matches("S3cret-pass", hash)).isTrue();
        assertThat(hasher.matches("other", hash)).isFalse();
    }
}
