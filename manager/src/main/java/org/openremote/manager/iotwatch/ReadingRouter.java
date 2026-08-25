package org.openremote.manager.iotwatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Picks the reading(s) a single asset should decode from a device message, so a message
 * fanned out to several assets that share a devEui lands the right data on each, and
 * resolves the time each reading is stored under.
 *
 * <p>Candidate readings are {@code data_decoded.readings} when present, otherwise the flat
 * {@code data_decoded} as one reading. A reading tagged with {@code external_id} belongs to
 * exactly one meter and is consumed only by the asset whose selector matches (full targets).
 * An untagged reading is shared by field name: the asset consumes its targets minus any field
 * a sibling also claims ({@code contestedNames}). The returned messages are re-wrapped as
 * {@code {"data_decoded": <reading>}} so the existing {@link DeviceDecoder}s decode them
 * unchanged (including the per-reading decoded/ok failure guard).
 *
 * <p>Each routed reading carries the time the measurement was taken: its own
 * {@code measured_at} (epoch millis), else a batch-level {@code measured_at} sitting next to
 * the {@code readings} list, else the message timestamp. An implausible value is rejected
 * rather than clamped — a broken device clock must not file data at an invented time — and
 * reported as a warning.
 */
public final class ReadingRouter {

    public record Routed(Map<String, Object> message, Set<String> effectiveTargets, long timestamp) {}

    /** {@code warnings} carries diagnostics for dropped/ambiguous readings as data; the caller logs them. */
    public record RouteResult(List<Routed> routed, List<String> warnings) {}

    /** A candidate reading with its resolved measurement time. */
    private record Candidate(Map<String, Object> reading, long timestamp) {}

    private static final String READINGS = "readings";
    private static final String EXTERNAL_ID = "external_id";
    private static final String MEASURED_AT = "measured_at";

    /** A measurement time may be at most this far before the message timestamp. */
    public static final long MAX_BACKDATE_MILLIS = 365L * 24 * 60 * 60 * 1000;

    /** ...and at most this far after it, to tolerate device clock skew. */
    public static final long MAX_FUTURE_SKEW_MILLIS = 24L * 60 * 60 * 1000;

    private ReadingRouter() {
    }

    @SuppressWarnings("unchecked")
    public static RouteResult route(Map<String, Object> message, String selector,
                                    Set<String> targetNames, Set<String> contestedNames,
                                    long messageTimestamp) {
        Object dd = message.get(IotWatchDecodeService.DATA_DECODED_KEY);
        if (!(dd instanceof Map)) {
            return new RouteResult(List.of(), List.of());
        }
        Map<String, Object> decoded = (Map<String, Object>) dd;

        List<String> warnings = new ArrayList<>();
        List<Candidate> candidates = candidates(decoded, messageTimestamp, warnings);

        List<Candidate> tagged = new ArrayList<>();
        List<Routed> result = new ArrayList<>();
        Set<String> untaggedTargets = targetNames.stream()
            .filter(n -> !contestedNames.contains(n))
            .collect(Collectors.toUnmodifiableSet());

        for (Candidate candidate : candidates) {
            Object ext = candidate.reading().get(EXTERNAL_ID);
            if (ext != null) {
                if (selector != null && String.valueOf(ext).equals(selector)) {
                    tagged.add(candidate);
                }
            } else {
                result.add(routed(candidate, untaggedTargets));
                Set<String> droppedContested = candidate.reading().keySet().stream()
                    .filter(contestedNames::contains)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
                if (!droppedContested.isEmpty()) {
                    warnings.add("dropped contested field(s) " + droppedContested
                        + " from an untagged reading; set external_id to route it");
                }
            }
        }

        if (tagged.size() == 1) {
            result.add(routed(tagged.get(0), targetNames));
        } else if (tagged.size() > 1) {
            long distinctTimes = tagged.stream().mapToLong(Candidate::timestamp).distinct().count();
            if (distinctTimes == tagged.size()) {
                // A buffered history batch for this meter: one reading per measurement time.
                tagged.forEach(candidate -> result.add(routed(candidate, targetNames)));
            } else {
                // duplicate/mis-tagged payload → consume none of the tagged readings
                warnings.add("ignored " + tagged.size() + " readings tagged external_id '" + selector
                    + "' (two or more share a measurement time); wrote nothing");
            }
        }
        warnAboutCollisions(result, warnings);
        return new RouteResult(result, warnings);
    }

    /**
     * The readings of one message, each with its measurement time resolved. Either the entries
     * of {@code data_decoded.readings}, or the flat {@code data_decoded} as a single reading.
     */
    @SuppressWarnings("unchecked")
    private static List<Candidate> candidates(Map<String, Object> decoded, long messageTimestamp,
                                              List<String> warnings) {
        List<?> readings = readingList(decoded.get(READINGS));
        if (readings == null) {
            // Flat: data_decoded itself is the single reading and carries its own measured_at.
            return List.of(new Candidate(decoded,
                resolveTimestamp(decoded, messageTimestamp, messageTimestamp, warnings)));
        }
        // A measured_at next to the readings list is the default for entries without one.
        long batchTimestamp = resolveTimestamp(decoded, messageTimestamp, messageTimestamp, warnings);
        List<Candidate> candidates = new ArrayList<>();
        for (Object o : readings) {
            if (o instanceof Map) {
                Map<String, Object> reading = (Map<String, Object>) o;
                candidates.add(new Candidate(reading,
                    resolveTimestamp(reading, batchTimestamp, messageTimestamp, warnings)));
            }
        }
        return candidates;
    }

    /**
     * The {@code readings} value as a list, or {@code null} when it is not a JSON array.
     *
     * <p>Both array representations must be recognised. The production path deserialises the
     * message with {@code ValueUtil.JSON}, which enables
     * {@code DeserializationFeature.USE_JAVA_ARRAY_FOR_JSON_ARRAY}, so an untyped JSON array
     * arrives as {@code Object[]}; a message assembled in code (or in a Groovy test) carries a
     * real {@code List}. Accepting only one of the two silently drops the whole batch.
     */
    private static List<?> readingList(Object value) {
        if (value instanceof Object[] array) {
            return Arrays.asList(array);
        }
        return value instanceof List<?> list ? list : null;
    }

    private static Routed routed(Candidate candidate, Set<String> effectiveTargets) {
        return new Routed(Map.of(IotWatchDecodeService.DATA_DECODED_KEY, candidate.reading()),
            effectiveTargets, candidate.timestamp());
    }

    /**
     * Two readings that resolve to the same timestamp and write the same field overwrite each
     * other in the data-point table, whose key is asset + attribute + timestamp. Readings are
     * grouped by resolved timestamp and each field's writes counted, so the cost is linear in
     * the number of routed readings and one warning is emitted per timestamp rather than per
     * colliding pair. The number of readings in a message is decoder-controlled input, and a
     * warning list quadratic in it can exhaust the manager heap.
     */
    private static void warnAboutCollisions(List<Routed> routed, List<String> warnings) {
        // insertion-ordered, so warnings come out in the order the readings arrived
        Map<Long, Map<String, Integer>> writesPerTimestamp = new LinkedHashMap<>();
        for (Routed reading : routed) {
            Map<String, Integer> writeCounts =
                writesPerTimestamp.computeIfAbsent(reading.timestamp(), t -> new LinkedHashMap<>());
            for (String field : writtenFields(reading)) {
                writeCounts.merge(field, 1, Integer::sum);
            }
        }
        writesPerTimestamp.forEach((timestamp, writeCounts) -> {
            Set<String> collidingFields = writeCounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            if (!collidingFields.isEmpty()) {
                warnings.add("readings collide on field(s) " + collidingFields + " at timestamp "
                    + timestamp + "; only the last one is stored — give each reading a"
                    + " distinct measured_at");
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static Set<String> writtenFields(Routed routed) {
        Map<String, Object> reading =
            (Map<String, Object>) routed.message().get(IotWatchDecodeService.DATA_DECODED_KEY);
        return reading.keySet().stream()
            .filter(routed.effectiveTargets()::contains)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * The measurement time of a reading: its own {@code measured_at} when present, integral and
     * inside the validity window, otherwise {@code fallback}. Rejected values are reported with
     * the value the decoder actually sent, not with a narrowed one.
     */
    private static long resolveTimestamp(Map<?, ?> reading, long fallback, long messageTimestamp,
                                         List<String> warnings) {
        Object raw = reading.get(MEASURED_AT);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Number number)) {
            warnings.add("ignored measured_at '" + raw + "' (not a number of epoch millis); used "
                + fallback);
            return fallback;
        }
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value != Math.floor(value)) {
            warnings.add("ignored measured_at '" + raw
                + "' (not a finite integral number of epoch millis); used " + fallback);
            return fallback;
        }
        // The window is checked before narrowing: BigInteger.longValue() truncates modulo 2^64
        // instead of saturating, so a JSON integer above 2^63 could otherwise wrap into the
        // window and be accepted silently. Epoch millis are far below 2^53, so comparing as
        // double is exact at these magnitudes and cannot wrap.
        if (value < (double) (messageTimestamp - MAX_BACKDATE_MILLIS)
            || value > (double) (messageTimestamp + MAX_FUTURE_SKEW_MILLIS)) {
            warnings.add("ignored measured_at " + raw + " outside the validity window around "
                + "message timestamp " + messageTimestamp + "; used " + fallback);
            return fallback;
        }
        return number.longValue();
    }
}
