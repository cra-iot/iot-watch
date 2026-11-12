package org.openremote.manager.iotwatch;

import org.openremote.model.iotwatch.FleetExportReport;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * A validated, inclusive range of days (daily report) or calendar months (monthly report) for the
 * fleet CSV export, always interpreted in Europe/Prague. For a monthly report from and to are the
 * first days of the first and last month. start is the first period at local 00:00 and end is the
 * period after the last one, exclusive, so a period with a DST change is still one period and the
 * bounds are never off by an hour.
 *
 * The last period must be finished: yesterday or earlier (daily), last month or earlier (monthly),
 * so its row would not change between two exports.
 */
public record FleetExportPeriod(FleetExportReport report, LocalDate from, LocalDate to) {

    public static final ZoneId ZONE = ZoneId.of("Europe/Prague");
    public static final int MAX_DAYS = 366;
    public static final int MAX_MONTHS = 24;

    /**
     * Throws IllegalArgumentException with a user-readable message, which the fleet app translates
     * by its wording (fleet-export.ts, serverProblem).
     */
    public static FleetExportPeriod parse(FleetExportReport report, String from, String to, Instant now) {
        if (report == null) {
            throw new IllegalArgumentException("Select a report");
        }
        LocalDate fromDate;
        LocalDate toDate;
        try {
            fromDate = LocalDate.parse(from);
            toDate = LocalDate.parse(to);
        } catch (DateTimeException | NullPointerException e) {
            throw new IllegalArgumentException("Dates must be given as yyyy-MM-dd");
        }
        boolean monthly = report == FleetExportReport.MONTHLY;
        if (monthly && (fromDate.getDayOfMonth() != 1 || toDate.getDayOfMonth() != 1)) {
            throw new IllegalArgumentException("Months must start on the first day");
        }
        if (fromDate.isAfter(toDate)) {
            throw new IllegalArgumentException("The first period must not be after the last");
        }
        if (monthly) {
            if (YearMonth.from(toDate).isAfter(lastMonth(now))) {
                throw new IllegalArgumentException("The last month must be last month or earlier");
            }
            if (ChronoUnit.MONTHS.between(fromDate, toDate) + 1 > MAX_MONTHS) {
                throw new IllegalArgumentException("The range must not exceed " + MAX_MONTHS + " months");
            }
        } else {
            if (toDate.isAfter(lastDay(now))) {
                throw new IllegalArgumentException("The last day must be yesterday or earlier");
            }
            if (ChronoUnit.DAYS.between(fromDate, toDate) + 1 > MAX_DAYS) {
                throw new IllegalArgumentException("The range must not exceed " + MAX_DAYS + " days");
            }
        }
        return new FleetExportPeriod(report, fromDate, toDate);
    }

    /** The latest day a daily report may end on: yesterday in Prague. */
    public static LocalDate lastDay(Instant now) {
        return LocalDate.ofInstant(now, ZONE).minusDays(1);
    }

    /** The latest month a monthly report may end on: the previous month in Prague. */
    public static YearMonth lastMonth(Instant now) {
        return YearMonth.from(LocalDate.ofInstant(now, ZONE)).minusMonths(1);
    }

    private boolean monthly() {
        return report == FleetExportReport.MONTHLY;
    }

    private LocalDate next(LocalDate periodStart) {
        return monthly() ? periodStart.plusMonths(1) : periodStart.plusDays(1);
    }

    /** The start of every period, from the first to the last inclusive. */
    public List<LocalDate> periods() {
        return from.datesUntil(next(to), monthly() ? Period.ofMonths(1) : Period.ofDays(1)).toList();
    }

    public int count() {
        return periods().size();
    }

    public Instant start() {
        return from.atStartOfDay(ZONE).toInstant();
    }

    public Instant end() {
        return next(to).atStartOfDay(ZONE).toInstant();
    }

    public String label(LocalDate periodStart) {
        return monthly() ? YearMonth.from(periodStart).toString() : periodStart.toString();
    }

    public String column() {
        return monthly() ? "month" : "date";
    }

    public String fileKey() {
        return monthly() ? "monthly" : "daily";
    }
}
