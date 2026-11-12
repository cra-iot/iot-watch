package org.openremote.manager.iotwatch;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CSV rendering and file names for the fleet export. Stateless helpers: cells are quoted per
 * RFC 4180 (only when needed), lines end with CRLF, and values are rendered the same way for
 * every column so a spreadsheet never sees scientific notation. A text cell a spreadsheet would
 * run as a formula gets a leading apostrophe (OWASP CSV injection); numbers are left as they are.
 */
public final class FleetExportCsv {

    /** Written first so Excel opens the file as UTF-8. */
    public static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    public static final List<String> FIXED_COLUMNS = List.of("date", "assetId", "name", "lastReadingAt");

    private static final String FORMULA_START = "=+-@\t\r";

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm'Z'").withZone(ZoneOffset.UTC);

    private FleetExportCsv() {
    }

    /** One CSV line ending with CRLF; a null cell is empty. */
    public static String line(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(quote(cells.get(i)));
        }
        return sb.append("\r\n").toString();
    }

    /** Plain-text form of an attribute value; numbers never use scientific notation. */
    public static String render(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        }
        if (value instanceof Object[] array) {
            return join(Arrays.asList(array));
        }
        if (value instanceof Collection<?> collection) {
            return join(collection);
        }
        return value.toString();
    }

    /** ISO-8601 UTC truncated to seconds; null is empty. */
    public static String instant(Instant at) {
        return at == null ? "" : DateTimeFormatter.ISO_INSTANT.format(at.truncatedTo(ChronoUnit.SECONDS));
    }

    public static String csvName(String assetType, FleetExportRange range, Instant exportedAt) {
        return stem(assetType, range, exportedAt) + ".csv";
    }

    public static String zipName(FleetExportRange range, Instant exportedAt) {
        return stem("fleet-export", range, exportedAt) + ".zip";
    }

    private static String stem(String prefix, FleetExportRange range, Instant exportedAt) {
        String name = prefix + "_" + range.from() + "_" + range.to() + "_" + STAMP.format(exportedAt);
        return range.utc() ? name : name + "_" + range.zone().getId().replace('/', '-');
    }

    private static String join(Collection<?> items) {
        return items.stream().map(FleetExportCsv::render).collect(Collectors.joining("; "));
    }

    private static String quote(String value) {
        if (value == null) {
            return "";
        }
        String cell = neutralize(value);
        if (cell.indexOf(',') < 0 && cell.indexOf('"') < 0 && cell.indexOf('\n') < 0 && cell.indexOf('\r') < 0) {
            return cell;
        }
        return '"' + cell.replace("\"", "\"\"") + '"';
    }

    /** Prefixes a cell starting with a formula character; a number such as -5 is no formula. */
    private static String neutralize(String cell) {
        if (cell.isEmpty() || FORMULA_START.indexOf(cell.charAt(0)) < 0 || isNumber(cell)) {
            return cell;
        }
        return "'" + cell;
    }

    private static boolean isNumber(String cell) {
        try {
            new BigDecimal(cell);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
