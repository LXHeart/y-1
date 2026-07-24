package com.grassland.identity.assertion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** {@link IdentityAssertion#isService()} / {@link IdentityAssertion#isUser()} 调用方种类判定（HLD 11.1）。 */
class IdentityAssertionTest {

    private static final Instant ISSUED = Instant.parse("2026-07-25T12:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-07-25T12:01:00Z");

    @Test
    void nullCallerKind_isUser() {
        var user = new IdentityAssertion(
                "11111111-1111-1111-1111-111111111111", "merchant", "sid", null, null,
                "cookie-session", "level1", null, "r", "t",
                "grassland-internal", ISSUED, EXPIRES, null, null);

        assertThat(user.isService()).isFalse();
        assertThat(user.isUser()).isTrue();
    }

    @Test
    void explicitUserCallerKind_isUser() {
        var user = new IdentityAssertion(
                "11111111-1111-1111-1111-111111111111", "merchant", "sid", null, null,
                "cookie-session", "level1", null, "r", "t",
                "grassland-internal", ISSUED, EXPIRES, "user", null);

        assertThat(user.isUser()).isTrue();
    }

    @Test
    void serviceCallerKind_isService() {
        var service = new IdentityAssertion(
                "22222222-2222-2222-2222-222222222222", null, null,
                "33333333-3333-3333-3333-333333333333", null,
                "service", "internal", null, "r", "t",
                "grassland-internal", ISSUED, EXPIRES, "service", "marketplace");

        assertThat(service.isService()).isTrue();
        assertThat(service.isUser()).isFalse();
        assertThat(service.principal()).isEqualTo("marketplace");
    }
}
