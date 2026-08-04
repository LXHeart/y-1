package com.grassland.identity.assertion.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 验签铁律：任何失败一律 empty（不抛），调用方据此降级匿名。 */
class AccessTokenSignerTest {

    private static final byte[] SECRET = "mobile-access-token-secret-32chars!!".getBytes();
    private static final Instant NOW = Instant.parse("2026-08-04T08:00:00Z");

    private final AccessTokenSigner signer = new AccessTokenSigner(SECRET, "access-token-v1", Duration.ofSeconds(5));

    private AccessToken token(Instant issued, Instant expires) {
        return new AccessToken("11111111-1111-1111-1111-111111111111", "user@example.com", "user",
                "device-abc", "22222222-2222-2222-2222-222222222222", "access-token-v1",
                issued.getEpochSecond(), expires.getEpochSecond());
    }

    @Test
    void signAndVerify_roundTrips() {
        String signed = signer.sign(token(NOW, NOW.plusSeconds(900)));

        assertThat(signer.verify(signed, NOW))
                .get()
                .satisfies(t -> {
                    assertThat(t.accountId()).isEqualTo("11111111-1111-1111-1111-111111111111");
                    assertThat(t.sessionToken()).isEqualTo("22222222-2222-2222-2222-222222222222");
                    assertThat(t.kid()).isEqualTo("access-token-v1");
                });
    }

    @Test
    void tamperedPayload_rejected() {
        String signed = signer.sign(token(NOW, NOW.plusSeconds(900)));
        String tampered = "AAAA" + signed.substring(4);

        assertThat(signer.verify(tampered, NOW)).isEmpty();
    }

    @Test
    void tamperedMac_rejected() {
        String signed = signer.sign(token(NOW, NOW.plusSeconds(900)));
        int dot = signed.indexOf('.');
        String tampered = signed.substring(0, dot + 1) + "AAAA" + signed.substring(dot + 5);

        assertThat(signer.verify(tampered, NOW)).isEmpty();
    }

    @Test
    void expired_rejected() {
        String signed = signer.sign(token(NOW.minusSeconds(1800), NOW.minusSeconds(900)));

        assertThat(signer.verify(signed, NOW)).isEmpty();
    }

    @Test
    void expiredWithinLeeway_accepted() {
        String signed = signer.sign(token(NOW.minusSeconds(1800), NOW.minusSeconds(3)));

        assertThat(signer.verify(signed, NOW)).isPresent();
    }

    @Test
    void issuedInFutureBeyondLeeway_rejected() {
        String signed = signer.sign(token(NOW.plusSeconds(60), NOW.plusSeconds(900)));

        assertThat(signer.verify(signed, NOW)).isEmpty();
    }

    @Test
    void issuedSlightlyInFutureWithinLeeway_accepted() {
        String signed = signer.sign(token(NOW.plusSeconds(3), NOW.plusSeconds(900)));

        assertThat(signer.verify(signed, NOW)).isPresent();
    }

    @Test
    void kidMismatch_rejected() {
        AccessTokenSigner other = new AccessTokenSigner(SECRET, "access-token-v2", Duration.ofSeconds(5));
        String signedByOther = other.sign(token(NOW, NOW.plusSeconds(900)));

        assertThat(signer.verify(signedByOther, NOW)).isEmpty();
    }

    @Test
    void differentSecret_rejected() {
        AccessTokenSigner other = new AccessTokenSigner("another-secret-32-chars-minimum!!".getBytes(),
                "access-token-v1", Duration.ofSeconds(5));
        String signedByOther = other.sign(token(NOW, NOW.plusSeconds(900)));

        assertThat(signer.verify(signedByOther, NOW)).isEmpty();
    }

    @Test
    void malformedTokens_rejectedWithoutThrow() {
        assertThat(signer.verify(null, NOW)).isEmpty();
        assertThat(signer.verify("", NOW)).isEmpty();
        assertThat(signer.verify("no-dot", NOW)).isEmpty();
        assertThat(signer.verify(".starts-with-dot", NOW)).isEmpty();
        assertThat(signer.verify("ends-with-dot.", NOW)).isEmpty();
        assertThat(signer.verify("!!!.###", NOW)).isEmpty();
    }

    @Test
    void nullNow_usesSystemClock() {
        String signed = signer.sign(token(Instant.now().minusSeconds(10), Instant.now().plusSeconds(900)));

        assertThat(signer.verify(signed, null)).isPresent();
    }

    @Test
    void emptySecret_unconfigured() {
        AccessTokenSigner unconfigured = new AccessTokenSigner(new byte[0], "access-token-v1", null);

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThat(unconfigured.verify(signer.sign(token(NOW, NOW.plusSeconds(900))), NOW)).isEmpty();
        assertThatThrownBy(() -> unconfigured.sign(token(NOW, NOW.plusSeconds(900))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nullSecret_unconfigured() {
        assertThat(new AccessTokenSigner(null, "access-token-v1", null).isConfigured()).isFalse();
    }

    @Test
    void blankKid_rejected() {
        assertThatThrownBy(() -> new AccessTokenSigner(SECRET, " ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configured_signerReportsKid() {
        assertThat(signer.isConfigured()).isTrue();
        assertThat(signer.kid()).isEqualTo("access-token-v1");
    }
}
