package org.openremote.manager.iotwatch;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the per-period rows of the fleet CSV export. For every attribute a period has the reading
 * carried in from before it (start), the reading carried out at its end, and their difference
 * (consumption). A period without a new reading repeats the previous one, including the last value
 * from before the range, so a measurement is never invented: one with no known value is null.
 * endReadingAt exposes the age of the data, so a dead meter does not look like zero consumption.
 */
public final class PeriodRows {

    public record Reading(String value, Instant at) {}

    public record Values(String start, String end, String consumption) {}

    public record PeriodRow(LocalDate period, Instant endReadingAt, Map<String, Values> values) {}

    private PeriodRows() {
    }

    /**
     * One row per period. start is the carried reading before the period's own reading is applied,
     * end the carried reading after it; seed is the last reading before the range. consumption is
     * end - start, null when either side is missing or not a number. endReadingAt is the time of the
     * end reading of the first attribute, null when it has none.
     */
    public static List<PeriodRow> build(List<LocalDate> periods, List<String> attributes,
                                        Map<String, Reading> seed, Map<LocalDate, Map<String, Reading>> lastPerPeriod) {
        Map<String, Reading> current = new HashMap<>(seed);
        List<PeriodRow> rows = new ArrayList<>(periods.size());
        for (LocalDate period : periods) {
            Map<String, Reading> before = new HashMap<>(current);
            Map<String, Reading> inPeriod = lastPerPeriod.get(period);
            if (inPeriod != null) {
                inPeriod.forEach((attribute, reading) -> {
                    if (reading != null) {
                        current.put(attribute, reading);
                    }
                });
            }
            Map<String, Values> values = new LinkedHashMap<>();
            for (String attribute : attributes) {
                values.put(attribute, values(before.get(attribute), current.get(attribute)));
            }
            Reading first = attributes.isEmpty() ? null : current.get(attributes.get(0));
            rows.add(new PeriodRow(period, first == null ? null : first.at(), values));
        }
        return rows;
    }

    /** Plain rendering of a numeric string; raw unchanged when it is not a number, null for null. */
    public static String number(String raw) {
        BigDecimal parsed = parse(raw);
        return parsed == null ? raw : plain(parsed);
    }

    private static Values values(Reading start, Reading end) {
        String startValue = start == null ? null : start.value();
        String endValue = end == null ? null : end.value();
        BigDecimal from = parse(startValue);
        BigDecimal to = parse(endValue);
        String consumption = from == null || to == null ? null : plain(to.subtract(from));
        return new Values(number(startValue), number(endValue), consumption);
    }

    private static final int MAX_SCALE = 1000;

    private static BigDecimal parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            BigDecimal parsed = new BigDecimal(raw.trim());
            // a huge exponent would make toPlainString allocate gigabytes
            return Math.abs((long) parsed.scale()) > MAX_SCALE ? null : parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String plain(BigDecimal value) {
        // stripTrailingZeros turns zero into "0" since Java 8, so no "0E+1" style renderings
        return value.stripTrailingZeros().toPlainString();
    }
}
