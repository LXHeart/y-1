package com.grassland.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordVerifierTest {
    private final Argon2PasswordHasher argon2Hasher = new Argon2PasswordHasher();
    private final PasswordVerifier verifier = new PasswordVerifier(argon2Hasher);

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
        String hash = argon2Hasher.hash("S3cret-pass");
        assertThat(verifier.verify("S3cret-pass", hash)).isTrue();
        assertThat(verifier.verify("other", hash)).isFalse();
    }

    @Test
    void detectTypeBcrypt() {
        String hash = at.favre.lib.crypto.bcrypt.BCrypt.withDefaults()
            .hashToString(12, "password".toCharArray());
        assertThat(verifier.detectType(hash)).isEqualTo(PasswordVerifier.PasswordType.BCRYPT);
    }

    @Test
    void detectTypeArgon2id() {
        String hash = argon2Hasher.hash("password");
        assertThat(verifier.detectType(hash)).isEqualTo(PasswordVerifier.PasswordType.ARGON2ID);
    }

    @Test
    void detectTypeUnknown() {
        assertThat(verifier.detectType("")).isEqualTo(PasswordVerifier.PasswordType.UNKNOWN);
        assertThat(verifier.detectType("$x$")).isEqualTo(PasswordVerifier.PasswordType.UNKNOWN);
        assertThat(verifier.detectType(null)).isEqualTo(PasswordVerifier.PasswordType.UNKNOWN);
    }

    @Test
    void needsRehashBcrypt() {
        String hash = at.favre.lib.crypto.bcrypt.BCrypt.withDefaults()
            .hashToString(12, "password".toCharArray());
        assertThat(verifier.needsRehash(hash)).isTrue();
    }

    @Test
    void needsRehashArgon2id() {
        String hash = argon2Hasher.hash("password");
        assertThat(verifier.needsRehash(hash)).isFalse();
    }
}
