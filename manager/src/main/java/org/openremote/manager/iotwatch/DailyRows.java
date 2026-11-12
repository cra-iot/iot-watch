package org.openremote.manager.iotwatch;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the per-day rows of the fleet CSV export. A day without a new reading repeats the previous
 * value, including the last value from before the range, so a measurement is never invented: one
 * with no known value is null. lastReadingAt exposes the age of the data, so a dead meter does not
 * look like zero consumption.
 */
public final class DailyRows {

    public record Reading(String value, Instant at) {}

    public record DailyRow(LocalDate date, Instant lastReadingAt, Map<String, String> values) {}

    private DailyRows() {
    }

    /**
     * One row per date. A measurement's value on a date is its daily reading of that date, else the
     * value carried from the previous date, starting from seed (the last reading before the range).
     * lastReadingAt = latest Reading.at among the values in that row; null when the row has none.
     * values contains every measurement key; a measurement with no known value maps to null.
     */
    public static List<DailyRow> build(List<LocalDate> dates, List<String> measurements,
                                       Map<String, Reading> seed, Map<LocalDate, Map<String, Reading>> daily) {
        Map<String, Reading> current = new HashMap<>(seed);
        List<DailyRow> rows = new ArrayList<>(dates.size());
        for (LocalDate date : dates) {
            Map<String, Reading> today = daily.get(date);
            if (today != null) {
                today.forEach((measurement, reading) -> {
                    if (reading != null) {
                        current.put(measurement, reading);
                    }
                });
            }
            Map<String, String> values = new LinkedHashMap<>();
            Instant last = null;
            for (String measurement : measurements) {
                Reading reading = current.get(measurement);
                values.put(measurement, reading == null ? null : reading.value());
                if (reading != null && reading.at() != null && (last == null || reading.at().isAfter(last))) {
                    last = reading.at();
                }
            }
            rows.add(new DailyRow(date, last, values));
        }
        return rows;
    }
}
