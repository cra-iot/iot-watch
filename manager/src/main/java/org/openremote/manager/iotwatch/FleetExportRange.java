package org.openremote.manager.iotwatch;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * A validated, inclusive range of calendar days for the fleet CSV export, interpreted in one time
 * zone. start is the first day at 00:00 in that zone and end is the day after the last one,
 * exclusive, so a day with a DST change is still one day and the bounds are never off by an hour.
 *
 * The last day must be yesterday or earlier in the zone: today is not finished, so its row would
 * change between two exports.
 */
public record FleetExportRange(LocalDate from, LocalDate to, ZoneId zone, Instant start, Instant end) {

    public static final int MAX_DAYS = 366;

    /** Throws IllegalArgumentException with a user-readable message. timeZone null/blank = UTC. */
    public static FleetExportRange parse(String from, String to, String timeZone, Instant now) {
        ZoneId zone;
        LocalDate fromDate;
        LocalDate toDate;
        try {
            zone = timeZone == null || timeZone.isBlank() ? ZoneOffset.UTC : ZoneId.of(timeZone.trim());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Unknown time zone: " + timeZone);
        }
        try {
            fromDate = LocalDate.parse(from);
            toDate = LocalDate.parse(to);
        } catch (DateTimeException | NullPointerException e) {
            throw new IllegalArgumentException("Dates must be given as yyyy-MM-dd");
        }
        if (fromDate.isAfter(toDate)) {
            throw new IllegalArgumentException("The first day must not be after the last day");
        }
        if (toDate.isAfter(maxTo(zone, now))) {
            throw new IllegalArgumentException("The last day must be yesterday or earlier");
        }
        if (ChronoUnit.DAYS.between(fromDate, toDate) + 1 > MAX_DAYS) {
            throw new IllegalArgumentException("The range must not exceed " + MAX_DAYS + " days");
        }
        return new FleetExportRange(fromDate, toDate, zone,
                fromDate.atStartOfDay(zone).toInstant(),
                toDate.plusDays(1).atStartOfDay(zone).toInstant());
    }

    /** The latest day an export may end on: yesterday in the zone. */
    public static LocalDate maxTo(ZoneId zone, Instant now) {
        return LocalDate.ofInstant(now, zone).minusDays(1);
    }

    public int days() {
        return (int) ChronoUnit.DAYS.between(from, to) + 1;
    }

    public List<LocalDate> dates() {
        return from.datesUntil(to.plusDays(1)).toList();
    }

    public boolean utc() {
        return zone.normalized().equals(ZoneOffset.UTC);
    }
}
