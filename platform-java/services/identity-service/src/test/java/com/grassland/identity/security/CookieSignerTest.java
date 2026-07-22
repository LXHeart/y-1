package com.grassland.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CookieSignerTest {
    private final CookieSigner signer = new CookieSigner("super-secret-key-32-chars-min!!");

    @Test
    void signUnsignRoundTrip() {
        String signed = signer.signCookie("abc123");
        assertThat(signer.unsign(signed)).contains("abc123");
    }

    @Test
    void acceptsRawSidWithoutSPrefix() {
        String signed = signer.sign("abc");
        assertThat(signer.unsign(signed)).contains("abc");
    }

    @Test
    void rejectsTamperedMac() {
        String signed = signer.signCookie("abc");
        String tampered = signed.substring(0, signed.length() - 1) + "X";
        assertThat(signer.unsign(tampered)).isEmpty();
    }

    @Test
    void rejectsUnsignedValue() {
        assertThat(signer.unsign("plain-no-dot")).isEmpty();
        assertThat(signer.unsign(null)).isEmpty();
        assertThat(signer.unsign("")).isEmpty();
    }

    @Test
    void unconfiguredSignerRejectsAll() {
        CookieSigner empty = new CookieSigner("");
        assertThat(empty.isConfigured()).isFalse();
        assertThat(empty.unsign("s:x.anything")).isEmpty();
    }
}
