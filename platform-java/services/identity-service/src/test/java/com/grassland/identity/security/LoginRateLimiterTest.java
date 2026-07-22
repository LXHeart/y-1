package com.grassland.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoginRateLimiterTest {
    private final LoginRateLimiter limiter = new LoginRateLimiter(60000, 3, 2);

    @Test
    void allowsUntilIpMaxReached() {
        for (int i = 0; i < 2; i++) {
            assertThat(limiter.check("1.2.3.4", null).allowed()).isTrue();
        }
        assertThat(limiter.check("1.2.3.4", null).allowed()).isFalse();
    }

    @Test
    void accountIpMaxBlocksEarlier() {
        assertThat(limiter.check("1.2.3.4", "user@example.com").allowed()).isTrue();
        assertThat(limiter.check("1.2.3.4", "user@example.com").allowed()).isFalse();
    }

    @Test
    void successRefundsCounter() {
        limiter.check("5.6.7.8", "a@b.com");
        limiter.recordOutcome("5.6.7.8", "a@b.com", false);
        assertThat(limiter.check("5.6.7.8", "a@b.com").allowed()).isTrue();
    }

    @Test
    void authFailureKeepsCounter() {
        limiter.check("9.0.0.1", "c@d.com");
        limiter.recordOutcome("9.0.0.1", "c@d.com", true);
        assertThat(limiter.check("9.0.0.1", "c@d.com").allowed()).isFalse();
    }
}
