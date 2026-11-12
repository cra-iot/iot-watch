package org.openremote.manager.iotwatch;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.openremote.model.iotwatch.FleetColumns;

/**
 * CSV rendering and file names for the fleet export. Stateless helpers: cells are quoted per
 * RFC 4180 (only when needed), lines end with CRLF, and values are rendered the same way for
 * every column so a spreadsheet never sees scientific notation. A text cell a spreadsheet would
 * run as a formula gets a leading apostrophe (OWASP CSV injection); numbers are left as they are.
 */
public final class FleetExportCsv {

    /** Written first so Excel opens the file as UTF-8. */
    public static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** The columns of every register group, in order. */
    public static final List<String> GROUP_PARTS = List.of("start", "end", "consumption");

    private static final String FORMULA_START = "=+-@\t\r";

    /** A cell that is exactly one Excel string literal, as text() writes it; it evaluates to plain text. */
    private static final Pattern TEXT_LITERAL = Pattern.compile("=\"(?:[^\"]|\"\")*\"");

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

    /**
     * An identifier as an Excel text formula, ="value": opened in Excel, an 18-digit EAN would
     * otherwise become 8.59182E+17 and lose digits, and a leading zero would vanish. Empty stays empty.
     */
    public static String text(String value) {
        return value == null || value.isEmpty() ? value : "=\"" + value.replace("\"", "\"\"") + "\"";
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

    /**
     * The period column, assetId, name, the identity columns (FleetColumns.EXPORT_IDENTITY),
     * end_reading_at, then start, end and consumption of every group.
     */
    public static List<String> header(FleetExportPeriod period, List<FleetExportRegisters.Group> groups) {
        List<String> header = new ArrayList<>();
        header.add(period.column());
        header.add("assetId");
        header.add("name");
        header.addAll(FleetColumns.EXPORT_IDENTITY);
        header.add("end_reading_at");
        for (FleetExportRegisters.Group group : groups) {
            for (String part : GROUP_PARTS) {
                header.add(FleetExportRegisters.header(group, part));
            }
        }
        return header;
    }

    /** assetType_report_from_to_stamp.csv; from and to as yyyy-MM (monthly) or yyyy-MM-dd (daily). */
    public static String fileName(String assetType, FleetExportPeriod period, Instant exportedAt) {
        return assetType + "_" + period.fileKey() + "_" + period.label(period.from()) + "_"
                + period.label(period.to()) + "_" + STAMP.format(exportedAt) + ".csv";
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

    /**
     * Prefixes a cell starting with a formula character; a number such as -5 is no formula, and
     * neither is a lone string literal such as text() writes.
     */
    private static String neutralize(String cell) {
        if (cell.isEmpty() || FORMULA_START.indexOf(cell.charAt(0)) < 0 || isNumber(cell)
                || TEXT_LITERAL.matcher(cell).matches()) {
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
