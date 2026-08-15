package com.grassland.marketplace.workflow.saga;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Configurable China business-day calendar for SLA deadlines. */
@Component
public class BusinessDayCalendar {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final Set<LocalDate> holidays;
    private final Set<LocalDate> workingDays;

    @org.springframework.beans.factory.annotation.Autowired
    public BusinessDayCalendar(
            @org.springframework.beans.factory.annotation.Value("${marketplace.confirmation.holidays:}") String holidays,
            @org.springframework.beans.factory.annotation.Value("${marketplace.confirmation.working-days:}") String workingDays) {
        this.holidays = parseDates(holidays);
        this.workingDays = parseDates(workingDays);
    }

    BusinessDayCalendar(Set<LocalDate> holidays, Set<LocalDate> workingDays) {
        this.holidays = Set.copyOf(holidays);
        this.workingDays = Set.copyOf(workingDays);
    }

    public boolean isBusinessDay(LocalDate date) {
        if (workingDays.contains(date)) return true;
        return date.getDayOfWeek() != DayOfWeek.SATURDAY
                && date.getDayOfWeek() != DayOfWeek.SUNDAY
                && !holidays.contains(date);
    }

    public Instant addBusinessDays(Instant start, int days) {
        ZonedDateTime cursor = start.atZone(ZONE);
        int remaining = Math.max(0, days);
        while (remaining > 0) {
            cursor = cursor.plusDays(1);
            if (isBusinessDay(cursor.toLocalDate())) remaining--;
        }
        return cursor.toInstant();
    }

    public long secondsUntil(Instant deadline, Instant now) {
        return Math.max(0L, Duration.between(now, deadline).getSeconds());
    }

    private static Set<LocalDate> parseDates(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).map(LocalDate::parse)
                .collect(Collectors.toUnmodifiableSet());
    }
}
