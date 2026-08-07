package com.grassland.finance.credits;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Computes a bounded daily AI quota from an immutable reputation entitlement snapshot. */
@Component
public final class AiQuotaPolicy {

    private static final int MAX_BASE_DAILY = 1_000_000;

    private final int baseDaily;
    private final ZoneId zoneId;
    private final Clock clock;

    @Autowired
    public AiQuotaPolicy(
            @Value("${credits.ai-quota.base-daily:0}") int baseDaily,
            @Value("${credits.ai-quota.zone-id:Asia/Shanghai}") String zoneId) {
        this(baseDaily, requireZone(zoneId), Clock.systemUTC());
    }

    AiQuotaPolicy(int baseDaily, ZoneId zoneId, Clock clock) {
        if (baseDaily < 0 || baseDaily > MAX_BASE_DAILY) {
            throw new IllegalArgumentException("credits.ai-quota.base-daily must be between 0 and 1000000");
        }
        this.baseDaily = baseDaily;
        this.zoneId = java.util.Objects.requireNonNull(zoneId, "zoneId");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public int limitFor(int multiplierBps) {
        if (multiplierBps < 1_000 || multiplierBps > 100_000) {
            throw new IllegalArgumentException("aiQuotaMultiplierBps must be between 1000 and 100000");
        }
        return Math.toIntExact(((long) baseDaily * multiplierBps) / 10_000L);
    }

    public LocalDate quotaDay() {
        return LocalDate.ofInstant(clock.instant(), zoneId);
    }

    private static ZoneId requireZone(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("credits.ai-quota.zone-id is required");
        }
        return ZoneId.of(value.trim());
    }
}
