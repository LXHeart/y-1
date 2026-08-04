package com.grassland.identity.mobile;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 刷新限流单测（GL-P3-IDENTITY-001）。
 *
 * <p>注意计数约定与 {@code LoginRateLimiter} 一致：{@code check()} 先自增再比 {@code < max}，
 * 故 {@code max=N} 实际放行 N-1 次（第 N 次即拒）。这里按同一约定断言，不单独改写语义。
 */
class RefreshRateLimiterTest {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Test
    void allowsUntilIpMaxThenBlocks() {
        RefreshRateLimiter limiter = new RefreshRateLimiter(60_000, 3, 100);
        assertThat(limiter.check("1.1.1.1", HASH_A).allowed()).isTrue();
        assertThat(limiter.check("1.1.1.1", HASH_A).allowed()).isTrue();
        assertThat(limiter.check("1.1.1.1", HASH_A).allowed()).isFalse();
    }

    @Test
    void perTokenBudgetIsIndependentOfOtherTokens() {
        RefreshRateLimiter limiter = new RefreshRateLimiter(60_000, 100, 2);
        assertThat(limiter.check("2.2.2.2", HASH_A).allowed()).isTrue();
        assertThat(limiter.check("2.2.2.2", HASH_A).allowed()).isFalse();
        // 同 IP 换另一个 token：token 闸独立，仍放行。
        assertThat(limiter.check("2.2.2.2", HASH_B).allowed()).isTrue();
    }

    @Test
    void differentIpsDoNotShareBudget() {
        RefreshRateLimiter limiter = new RefreshRateLimiter(60_000, 2, 2);
        assertThat(limiter.check("3.3.3.3", HASH_A).allowed()).isTrue();
        assertThat(limiter.check("3.3.3.3", HASH_A).allowed()).isFalse();
        assertThat(limiter.check("4.4.4.4", HASH_A).allowed()).isTrue();
    }

    @Test
    void successfulRefreshRefundsBudget() {
        RefreshRateLimiter limiter = new RefreshRateLimiter(60_000, 2, 2);
        assertThat(limiter.check("5.5.5.5", HASH_A).allowed()).isTrue();
        limiter.recordOutcome("5.5.5.5", HASH_A, false);
        // 退还后预算回到起点，可再放行一次。
        assertThat(limiter.check("5.5.5.5", HASH_A).allowed()).isTrue();
        assertThat(limiter.check("5.5.5.5", HASH_A).allowed()).isFalse();
    }

    @Test
    void authFailureDoesNotRefund() {
        RefreshRateLimiter limiter = new RefreshRateLimiter(60_000, 2, 2);
        assertThat(limiter.check("6.6.6.6", HASH_A).allowed()).isTrue();
        limiter.recordOutcome("6.6.6.6", HASH_A, true);
        assertThat(limiter.check("6.6.6.6", HASH_A).allowed()).isFalse();
    }

    @Test
    void missingIpAlwaysAllowed() {
        RefreshRateLimiter limiter = new RefreshRateLimiter(60_000, 0, 0);
        assertThat(limiter.check(null, HASH_A).allowed()).isTrue();
        assertThat(limiter.check("", HASH_A).allowed()).isTrue();
    }

    @Test
    void windowResetsAfterExpiry() throws Exception {
        RefreshRateLimiter limiter = new RefreshRateLimiter(20, 2, 2);
        assertThat(limiter.check("7.7.7.7", HASH_A).allowed()).isTrue();
        assertThat(limiter.check("7.7.7.7", HASH_A).allowed()).isFalse();
        Thread.sleep(40);
        assertThat(limiter.check("7.7.7.7", HASH_A).allowed()).isTrue();
    }

    @Test
    void remainingCountsAreReported() {
        RefreshRateLimiter limiter = new RefreshRateLimiter(60_000, 5, 3);
        RefreshRateLimiter.CheckResult first = limiter.check("8.8.8.8", HASH_A);
        assertThat(first.ipRemaining()).isEqualTo(4);
        assertThat(first.tokenRemaining()).isEqualTo(2);
    }
}
