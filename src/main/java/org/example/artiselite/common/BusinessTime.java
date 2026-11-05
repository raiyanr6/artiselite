package org.example.artiselite.common;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The one place that decides what "today" and "the last N days" mean.
 * Always uses the business timezone, never the server's default (the server runs in UTC).
 */
@Component
public class BusinessTime {

    public static final int MAX_WINDOW_DAYS = 90;

    private final Clock clock;

    @Autowired
    public BusinessTime(@Value("${app.business-timezone:Asia/Kuala_Lumpur}") String zone) {
        this(Clock.system(ZoneId.of(zone)));
    }

    // Lets tests freeze time: new BusinessTime(Clock.fixed(...))
    public BusinessTime(Clock clock) {
        this.clock = clock;
    }

    public ZoneId zone() {
        return clock.getZone();
    }

    public ZonedDateTime now() {
        return ZonedDateTime.now(clock);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /**
     * "Last N days" = the N business-calendar days ending today, inclusive.
     * Movement records only store a date (no time), so this is the finest window they support.
     */
    public DateRange lastDays(int days) {
        if (days < 1 || days > MAX_WINDOW_DAYS) {
            throw new IllegalArgumentException("days must be between 1 and " + MAX_WINDOW_DAYS);
        }
        LocalDate today = today();
        return new DateRange(today.minusDays(days - 1L), today);
    }

    public record DateRange(LocalDate from, LocalDate to) {
    }
}
