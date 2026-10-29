package org.openremote.manager.iotwatch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.openremote.model.iotwatch.FleetQuery;
import org.openremote.model.iotwatch.FleetQueryResult;
import org.openremote.model.iotwatch.FleetRow;

/**
 * Everything that happens after findAll: deduplicate the free-text union, apply the last-seen
 * filter, sort, count facets over the whole match, then cut the page.
 *
 * The order matters. Facets and total describe the match, not the page; computing them after
 * paging would make every saved-view count wrong.
 */
public final class FleetResultAssembler {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 500;

    /** An asset is "silent" once nothing has been heard for this long. */
    public static final Duration SILENT_AFTER = Duration.ofHours(24);

    /** The status keys rows are grouped, counted and filtered (FleetQuery.status) by. */
    public static final Set<String> STATUSES = Set.of("alarm", "silent", "ok");

    private FleetResultAssembler() {
    }

    /**
     * The matched set with no page applied: deduplicated, last-seen and status filtered, sorted.
     * The overview pages this; the CSV export consumes it whole.
     */
    public static List<FleetRow> match(List<FleetRow> rows, FleetQuery query, long nowMillis) {
        return statusFilteredAndSorted(selected(rows, query, nowMillis), query, nowMillis);
    }

    /** Deduplicated and last-seen filtered; the status filter is not applied yet. */
    private static List<FleetRow> selected(List<FleetRow> rows, FleetQuery query, long nowMillis) {
        // Parsed once, before any row: a malformed duration is rejected even for an empty match.
        Long olderThreshold = query.seen == null || query.seen.olderThan == null
                ? null : nowMillis - parseDuration(query.seen.olderThan).toMillis();
        Long newerThreshold = query.seen == null || query.seen.newerThan == null
                ? null : nowMillis - parseDuration(query.seen.newerThan).toMillis();
        List<FleetRow> selected = new ArrayList<>(deduplicate(rows));
        selected.removeIf(row -> !matchesSeen(row, olderThreshold, newerThreshold));
        return selected;
    }

    private static List<FleetRow> statusFilteredAndSorted(List<FleetRow> selected, FleetQuery query, long nowMillis) {
        List<FleetRow> matched = new ArrayList<>(selected);
        if (query.status != null && !query.status.isEmpty()) {
            Set<String> status = new HashSet<>(query.status);
            matched.removeIf(row -> !status.contains(status(row, nowMillis)));
        }
        // When grouping, the group key sorts first, so each group's rows are contiguous across
        // server pages and the UI can label a group with its facet count rather than counting
        // the rows of one page.
        matched.sort(groupComparator(query.groupBy, nowMillis).thenComparing(comparator(query.sort)));
        return matched;
    }

    /** The key a row is grouped under; also the facet key for the same dimension. */
    public static String groupKey(FleetRow row, FleetQuery.GroupBy groupBy, long nowMillis) {
        return switch (groupBy == null ? FleetQuery.GroupBy.NONE : groupBy) {
            case PARENT -> nullToEmpty(row.parentId);
            case TYPE -> nullToEmpty(row.type);
            case STATUS -> status(row, nowMillis);
            case NONE -> "";
        };
    }

    private static Comparator<FleetRow> groupComparator(FleetQuery.GroupBy groupBy, long nowMillis) {
        return Comparator.comparing(row -> groupKey(row, groupBy, nowMillis));
    }

    private static String status(FleetRow row, long nowMillis) {
        if (row.alarms != null && !row.alarms.isEmpty()) {
            return "alarm";
        }
        long silentThreshold = nowMillis - SILENT_AFTER.toMillis();
        return row.lastSeen == null || row.lastSeen <= silentThreshold ? "silent" : "ok";
    }

    /** ISO-8601 duration; a malformed value is the caller's error, not a server error. */
    static Duration parseDuration(String value) {
        try {
            return Duration.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid ISO-8601 duration: " + value, e);
        }
    }

    public static FleetQueryResult assemble(List<FleetRow> rows, FleetQuery query, long nowMillis) {
        List<FleetRow> selected = selected(rows, query, nowMillis);
        List<FleetRow> matched = statusFilteredAndSorted(selected, query, nowMillis);

        FleetQueryResult result = new FleetQueryResult();
        result.total = matched.size();
        result.facets = facets(matched, nowMillis);
        // Before the status filter, so picking one status still shows how many rows the others hold.
        result.statusCounts = counted(selected, nowMillis, row -> List.of(status(row, nowMillis)));
        // Before the status filter: a pasted identifier that a status chip hides was still found.
        result.unmatchedText = unmatchedText(selected, query);

        // A collapsed group shows only its header, so its rows leave the paging and the open
        // groups' rows fill the pages instead; total and facets above still count them.
        List<FleetRow> paged = pageable(matched, query, nowMillis);
        result.paged = paged.size();

        int limit = query.limit <= 0 ? DEFAULT_LIMIT : Math.min(query.limit, MAX_LIMIT);
        int offset = Math.max(0, query.offset);
        result.offset = offset;
        result.limit = limit;
        result.rows = offset >= paged.size()
                ? List.of()
                : new ArrayList<>(paged.subList(offset, Math.min(offset + limit, paged.size())));
        return result;
    }

    private static List<FleetRow> pageable(List<FleetRow> matched, FleetQuery query, long nowMillis) {
        if (query.groupBy == null || query.groupBy == FleetQuery.GroupBy.NONE
                || query.collapsed == null || query.collapsed.isEmpty()) {
            return matched;
        }
        Set<String> collapsed = new HashSet<>(query.collapsed);
        return matched.stream()
                .filter(row -> !collapsed.contains(groupKey(row, query.groupBy, nowMillis)))
                .toList();
    }

    /**
     * The free-text values no matched row contains, ignoring case, in the fields the free-text
     * queries search (FleetQueryTranslator: the name and IDENTIFIER_ATTRIBUTES).
     */
    public static List<String> unmatchedText(List<FleetRow> matched, FleetQuery query) {
        List<String> texts = FleetQueryTranslator.texts(query);
        if (texts.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> haystack = new ArrayList<>();
        for (FleetRow row : matched) {
            for (String field : new String[]{row.name, row.deliveryPointId, row.meterId, row.devEui, row.externalId}) {
                if (field != null) {
                    haystack.add(field.toUpperCase(Locale.ROOT));
                }
            }
        }
        return new ArrayList<>(texts.stream()
                .filter(text -> {
                    String needle = text.toUpperCase(Locale.ROOT);
                    return haystack.stream().noneMatch(field -> field.contains(needle));
                })
                .toList());
    }

    private static List<FleetRow> deduplicate(List<FleetRow> rows) {
        Map<String, FleetRow> byId = new LinkedHashMap<>();
        rows.forEach(row -> byId.putIfAbsent(row.id, row));
        return new ArrayList<>(byId.values());
    }

    private static boolean matchesSeen(FleetRow row, Long olderThreshold, Long newerThreshold) {
        // A device that never reported is the most stale thing there is, so olderThan matches it.
        if (olderThreshold != null && row.lastSeen != null && row.lastSeen > olderThreshold) {
            return false;
        }
        if (newerThreshold != null) {
            return row.lastSeen != null && row.lastSeen >= newerThreshold;
        }
        return true;
    }

    private static Comparator<FleetRow> comparator(FleetQuery.Sort sort) {
        String field = sort == null || sort.field == null ? "name" : sort.field;
        boolean descending = sort != null && sort.descending;

        Comparator<FleetRow> comparator = switch (field) {
            // nullsLast keeps never-reported assets at the bottom in BOTH directions, which is
            // what an operator expects: "no data" is not the newest reading.
            case "lastSeen" -> {
                Comparator<Long> order = descending ? Comparator.reverseOrder() : Comparator.naturalOrder();
                yield Comparator.comparing(row -> row.lastSeen, Comparator.nullsLast(order));
            }
            case "lastReading" -> {
                Comparator<Long> order = descending ? Comparator.reverseOrder() : Comparator.naturalOrder();
                yield Comparator.comparing(row -> row.lastReading, Comparator.nullsLast(order));
            }
            case "type" -> nullSafeString(row -> row.type, descending);
            // Sorting by a register: "reading" means each row's own primary register, so a
            // mixed-type result sorts gas volume against electric kWh - which is why the UI only
            // offers it when one type is selected.
            case "reading" -> numericComparator(row -> numeric(row.values.get(row.primaryName)), descending);
            // FleetRow carries no creation time, so createdOn stays a name sort until it does.
            case "name", "createdOn" -> nullSafeString(row -> row.name, descending);
            // Any other field is an attribute name - the UI's column key is the bare descriptor
            // name - sorted numerically from row.values with missing values last. "values.<name>"
            // is kept as an alias.
            default -> {
                String attribute = field.startsWith("values.") ? field.substring(7) : field;
                yield numericComparator(row -> numeric(row.values.get(attribute)), descending);
            }
        };
        return comparator.thenComparing(row -> row.id, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static Comparator<FleetRow> numericComparator(Function<FleetRow, Double> extract,
                                                          boolean descending) {
        Comparator<Double> order = descending ? Comparator.reverseOrder() : Comparator.naturalOrder();
        return Comparator.comparing(extract, Comparator.nullsLast(order));
    }

    /** A value map holds Objects; anything non-numeric sorts as missing rather than throwing. */
    private static Double numeric(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Comparator<FleetRow> nullSafeString(
            java.util.function.Function<FleetRow, String> accessor, boolean descending) {
        Comparator<String> order = descending
                ? Comparator.reverseOrder()
                : Comparator.<String>naturalOrder();
        return Comparator.comparing(accessor, Comparator.nullsLast(order));
    }

    private static FleetQueryResult.Facets facets(List<FleetRow> rows, long nowMillis) {
        FleetQueryResult.Facets facets = new FleetQueryResult.Facets();
        facets.types = counted(rows, nowMillis, row -> List.of(nullToEmpty(row.type)));
        // Every row under an alarm key has an alarm, so each of them is a problem.
        facets.alarms = counted(rows, nowMillis, row -> row.alarms);
        facets.parents = counted(rows, nowMillis, row -> List.of(nullToEmpty(row.parentId)));
        facets.status = counted(rows, nowMillis, row -> List.of(status(row, nowMillis)));
        return facets;
    }

    /** A problem is an alarm or a silent device, computed over the whole match like every facet. */
    private static boolean isProblem(FleetRow row, long nowMillis) {
        return !"ok".equals(status(row, nowMillis));
    }

    private static List<FleetQueryResult.Facet> counted(
            List<FleetRow> rows, long nowMillis, java.util.function.Function<FleetRow, List<String>> keys) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (FleetRow row : rows) {
            boolean problem = isProblem(row, nowMillis);
            for (String key : keys.apply(row)) {
                if (!key.isEmpty()) {
                    int[] entry = counts.computeIfAbsent(key, k -> new int[2]);
                    entry[0]++;
                    if (problem) {
                        entry[1]++;
                    }
                }
            }
        }
        return toFacets(counts);
    }

    private static List<FleetQueryResult.Facet> toFacets(Map<String, int[]> counts) {
        List<FleetQueryResult.Facet> facets = new ArrayList<>();
        counts.forEach((key, entry) -> facets.add(new FleetQueryResult.Facet(key, null, entry[0], entry[1])));
        facets.sort(Comparator.comparingInt((FleetQueryResult.Facet facet) -> facet.count).reversed()
                .thenComparing(facet -> facet.key));
        return facets;
    }

    private static String nullToEmpty(String value) {
        return Objects.toString(value, "");
    }
}
