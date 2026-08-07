package com.grassland.finance.credits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AiQuotaPolicyTest {

    @Test
    void floorsBaseQuotaTimesMultiplier() {
        AiQuotaPolicy policy = new AiQuotaPolicy(3, ZoneId.of("Asia/Shanghai"), Clock.systemUTC());

        assertThat(policy.limitFor(10_000)).isEqualTo(3);
        assertThat(policy.limitFor(15_000)).isEqualTo(4);
    }

    @Test
    void computesQuotaDayAtConfiguredShanghaiBoundary() {
        Clock beforeMidnight = Clock.fixed(Instant.parse("2026-08-07T15:59:59Z"), ZoneOffset.UTC);
        Clock afterMidnight = Clock.fixed(Instant.parse("2026-08-07T16:00:00Z"), ZoneOffset.UTC);

        assertThat(new AiQuotaPolicy(2, ZoneId.of("Asia/Shanghai"), beforeMidnight).quotaDay())
                .hasToString("2026-08-07");
        assertThat(new AiQuotaPolicy(2, ZoneId.of("Asia/Shanghai"), afterMidnight).quotaDay())
                .hasToString("2026-08-08");
    }

    @Test
    void rejectsUnsafeConfiguration() {
        assertThatThrownBy(() -> new AiQuotaPolicy(-1, ZoneId.of("Asia/Shanghai"), Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
