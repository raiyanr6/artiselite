package org.example.artiselite.common;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BusinessTimeTest {

    private static final ZoneId KL = ZoneId.of("Asia/Kuala_Lumpur");
    // 01:30 on 26 Oct in Kuala Lumpur, but still 25 Oct in UTC (the server's zone)
    private static final Instant EARLY_MORNING_KL = Instant.parse("2026-10-25T17:30:00Z");

    private final BusinessTime businessTime = new BusinessTime(Clock.fixed(EARLY_MORNING_KL, KL));

    @Test
    void today_usesBusinessZone_notServerUtc() {
        assertThat(LocalDate.ofInstant(EARLY_MORNING_KL, ZoneOffset.UTC)).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(businessTime.today()).isEqualTo(LocalDate.of(2026, 10, 26));
    }

    @Test
    void lastSevenDays_isTheSevenCalendarDaysEndingToday() {
        BusinessTime.DateRange range = businessTime.lastDays(7);

        assertThat(range.from()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(range.to()).isEqualTo(LocalDate.of(2026, 10, 26));
    }

    @Test
    void lastOneDay_isJustToday() {
        BusinessTime.DateRange range = businessTime.lastDays(1);

        assertThat(range.from()).isEqualTo(range.to()).isEqualTo(LocalDate.of(2026, 10, 26));
    }

    @Test
    void windowOutsideAllowedRange_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> businessTime.lastDays(0));
        assertThrows(IllegalArgumentException.class, () -> businessTime.lastDays(BusinessTime.MAX_WINDOW_DAYS + 1));
    }
}
