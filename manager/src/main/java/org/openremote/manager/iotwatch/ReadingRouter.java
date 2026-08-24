package org.openremote.manager.iotwatch;

import java.util.ArrayList;
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
        List<Candidate> candidates = new ArrayList<>();
        if (decoded.get(READINGS) instanceof List<?> list) {
            // A measured_at next to the readings list is the default for entries without one.
            long batchTimestamp = resolveTimestamp(decoded, messageTimestamp, messageTimestamp, warnings);
            for (Object o : list) {
                if (o instanceof Map) {
                    Map<String, Object> reading = (Map<String, Object>) o;
                    candidates.add(new Candidate(reading,
                        resolveTimestamp(reading, batchTimestamp, messageTimestamp, warnings)));
                }
            }
        } else {
            // Flat: data_decoded itself is the single reading and carries its own measured_at.
            candidates.add(new Candidate(decoded,
                resolveTimestamp(decoded, messageTimestamp, messageTimestamp, warnings)));
        }

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
            // ambiguous/duplicate mis-tagged payload → consume none of the tagged readings
            warnings.add("ignored " + tagged.size() + " readings tagged external_id '" + selector
                + "' (ambiguous/duplicate); wrote nothing");
        }
        return new RouteResult(result, warnings);
    }

    private static Routed routed(Candidate candidate, Set<String> effectiveTargets) {
        return new Routed(Map.of(IotWatchDecodeService.DATA_DECODED_KEY, candidate.reading()),
            effectiveTargets, candidate.timestamp());
    }

    /**
     * The measurement time of a reading: its own {@code measured_at} when present, integral and
     * inside the validity window, otherwise {@code fallback}. Rejected values are reported.
     */
    private static long resolveTimestamp(Map<?, ?> reading, long fallback, long messageTimestamp,
                                         List<String> warnings) {
        Object raw = reading.get(MEASURED_AT);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Number number)
            || number.doubleValue() != Math.floor(number.doubleValue())) {
            warnings.add("ignored measured_at '" + raw + "' (not integral epoch millis); used " + fallback);
            return fallback;
        }
        long candidate = number.longValue();
        if (candidate < messageTimestamp - MAX_BACKDATE_MILLIS
            || candidate > messageTimestamp + MAX_FUTURE_SKEW_MILLIS) {
            warnings.add("ignored measured_at " + candidate + " outside the validity window around "
                + "message timestamp " + messageTimestamp + "; used " + fallback);
            return fallback;
        }
        return candidate;
    }
}
