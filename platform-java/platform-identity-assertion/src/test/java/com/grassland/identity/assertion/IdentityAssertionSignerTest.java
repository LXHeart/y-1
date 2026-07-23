package com.grassland.identity.assertion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 验签铁律：任何失败一律 empty（不抛），调用方据此回退 cookie。 */
class IdentityAssertionSignerTest {

    private static final byte[] SECRET = "super-secret-key".getBytes();
    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");

    private final IdentityAssertionSigner signer = new IdentityAssertionSigner(SECRET, "grassland-internal", Duration.ofSeconds(5));

    private IdentityAssertion assertion(Instant issued, Instant expires) {
        return new IdentityAssertion(
                "11111111-1111-1111-1111-111111111111",
                "merchant",
                "sid-abc",
                "cookie-session",
                "level1",
                null,
                "req-1",
                "trace-1",
                "grassland-internal",
                issued,
                expires);
    }

    @Test
    void signAndVerify_roundTrips() {
        String token = signer.sign(assertion(NOW, NOW.plusSeconds(60)));

        assertThat(signer.verify(token, NOW))
                .get()
                .satisfies(a -> {
                    assertThat(a.accountId()).isEqualTo("11111111-1111-1111-1111-111111111111");
                    assertThat(a.activeIdentityType()).isEqualTo("merchant");
                    assertThat(a.sessionToken()).isEqualTo("sid-abc");
                    assertThat(a.audience()).isEqualTo("grassland-internal");
                });
    }

    @Test
    void consumerAssertion_hasNullableActiveIdentity() {
        var consumer = new IdentityAssertion(
                "22222222-2222-2222-2222-222222222222", null, "sid-anon",
                "cookie-session", "level1", null, null, null,
                "grassland-internal", NOW, NOW.plusSeconds(60));
        String token = signer.sign(consumer);

        assertThat(signer.verify(token, NOW))
                .get()
                .satisfies(a -> assertThat(a.activeIdentityType()).isNull());
    }

    @Test
    void tamperedPayload_isRejected() {
        String token = signer.sign(assertion(NOW, NOW.plusSeconds(60)));
        // flip one character in the payload half (before the dot)
        char[] chars = token.toCharArray();
        chars[0] = chars[0] == 'A' ? 'B' : 'A';
        assertThat(signer.verify(new String(chars), NOW)).isEmpty();
    }

    @Test
    void tamperedMac_isRejected() {
        String token = signer.sign(assertion(NOW, NOW.plusSeconds(60)));
        int dot = token.indexOf('.');
        String tampered = token.substring(0, dot + 1) + (token.charAt(dot + 1) == 'A' ? 'B' : 'A') + token.substring(dot + 2);
        assertThat(signer.verify(tampered, NOW)).isEmpty();
    }

    @Test
    void malformedToken_isRejected() {
        assertThat(signer.verify("nodothere", NOW)).isEmpty();
        assertThat(signer.verify("payload.", NOW)).isEmpty();
        assertThat(signer.verify(".mac", NOW)).isEmpty();
        assertThat(signer.verify((String) null, NOW)).isEmpty();
        assertThat(signer.verify("  ", NOW)).isEmpty();
    }

    @Test
    void expiredBeyondLeeway_isRejected() {
        String token = signer.sign(assertion(NOW, NOW.plusSeconds(60)));
        // expiresAt + leeway(5) = NOW+65s; NOW+66s is past → rejected
        assertThat(signer.verify(token, NOW.plusSeconds(66))).isEmpty();
    }

    @Test
    void withinLeewayPastExpiry_isAccepted() {
        String token = signer.sign(assertion(NOW, NOW.plusSeconds(60)));
        // NOW+63s is within expiresAt(60)+leeway(5) → accepted
        assertThat(signer.verify(token, NOW.plusSeconds(63))).isPresent();
    }

    @Test
    void futureIssuedAtBeyondLeeway_isRejected() {
        // issued 100s in the future; leeway 5s → rejected
        String token = signer.sign(assertion(NOW.plusSeconds(100), NOW.plusSeconds(160)));
        assertThat(signer.verify(token, NOW)).isEmpty();
    }

    @Test
    void wrongAudience_isRejected() {
        IdentityAssertionSigner otherAudience =
                new IdentityAssertionSigner(SECRET, "some-other-audience", Duration.ofSeconds(5));
        String token = signer.sign(assertion(NOW, NOW.plusSeconds(60)));
        assertThat(otherAudience.verify(token, NOW)).isEmpty();
    }

    @Test
    void wrongSecret_isRejected() {
        IdentityAssertionSigner otherSecret =
                new IdentityAssertionSigner("different-secret".getBytes(), "grassland-internal", Duration.ofSeconds(5));
        String token = signer.sign(assertion(NOW, NOW.plusSeconds(60)));
        assertThat(otherSecret.verify(token, NOW)).isEmpty();
    }

    @Test
    void emptySecret_signThrowsAndVerifyEmpty() {
        IdentityAssertionSigner emptySecret = new IdentityAssertionSigner(new byte[0], "grassland-internal", Duration.ofSeconds(5));
        assertThatThrownBy(() -> emptySecret.sign(assertion(NOW, NOW.plusSeconds(60))))
                .isInstanceOf(IdentityAssertionException.class);
        assertThat(emptySecret.verify("payload.mac", NOW)).isEmpty();
    }
}
