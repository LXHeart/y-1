package com.grassland.marketplace.workflow.saga;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BusinessDayCalendarTest {
    @Test
    void skipsWeekendAndConfiguredHoliday() {
        BusinessDayCalendar calendar = new BusinessDayCalendar(
                Set.of(LocalDate.parse("2026-10-05")), Set.of());
        Instant friday = Instant.parse("2026-10-02T02:00:00Z"); // 10:00 Asia/Shanghai

        assertThat(calendar.addBusinessDays(friday, 1))
                .isEqualTo(Instant.parse("2026-10-06T02:00:00Z"));
    }

    @Test
    void configuredWorkingDayOverridesWeekend() {
        BusinessDayCalendar calendar = new BusinessDayCalendar(
                Set.of(), Set.of(LocalDate.parse("2026-10-03")));
        Instant friday = Instant.parse("2026-10-02T02:00:00Z");

        assertThat(calendar.addBusinessDays(friday, 1))
                .isEqualTo(Instant.parse("2026-10-03T02:00:00Z"));
    }

    @Test
    void durationNeverBecomesNegative() {
        BusinessDayCalendar calendar = new BusinessDayCalendar(Set.of(), Set.of());
        assertThat(calendar.secondsUntil(Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"))).isZero();
    }
}
