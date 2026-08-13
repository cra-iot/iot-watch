package org.openremote.manager.iotwatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Picks the reading(s) a single asset should decode from a device message, so a message
 * fanned out to several assets that share a devEui lands the right data on each.
 *
 * <p>Candidate readings are {@code data_decoded.readings} when present, otherwise the flat
 * {@code data_decoded} as one reading. A reading tagged with {@code external_id} belongs to
 * exactly one meter and is consumed only by the asset whose selector matches (full targets).
 * An untagged reading is shared by field name: the asset consumes its targets minus any field
 * a sibling also claims ({@code contestedNames}). The returned messages are re-wrapped as
 * {@code {"data_decoded": <reading>}} so the existing {@link DeviceDecoder}s decode them
 * unchanged (including the per-reading decoded/ok failure guard).
 */
public final class ReadingRouter {

    public record Routed(Map<String, Object> message, Set<String> effectiveTargets) {}

    private static final String READINGS = "readings";
    private static final String EXTERNAL_ID = "external_id";

    private ReadingRouter() {
    }

    @SuppressWarnings("unchecked")
    public static List<Routed> route(Map<String, Object> message, String selector,
                                     Set<String> targetNames, Set<String> contestedNames) {
        Object dd = message.get(IotWatchDecodeService.DATA_DECODED_KEY);
        if (!(dd instanceof Map)) {
            return List.of();
        }
        Map<String, Object> decoded = (Map<String, Object>) dd;

        List<Map<String, Object>> candidates = new ArrayList<>();
        if (decoded.get(READINGS) instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map) {
                    candidates.add((Map<String, Object>) o);
                }
            }
        } else {
            candidates.add(decoded);   // flat: data_decoded itself is the single reading
        }

        List<Map<String, Object>> tagged = new ArrayList<>();
        List<Routed> result = new ArrayList<>();
        Set<String> untaggedTargets = targetNames.stream()
            .filter(n -> !contestedNames.contains(n))
            .collect(Collectors.toUnmodifiableSet());

        for (Map<String, Object> reading : candidates) {
            Object ext = reading.get(EXTERNAL_ID);
            if (ext != null) {
                if (selector != null && String.valueOf(ext).equals(selector)) {
                    tagged.add(reading);
                }
            } else {
                result.add(new Routed(Map.of(IotWatchDecodeService.DATA_DECODED_KEY, reading), untaggedTargets));
            }
        }

        if (tagged.size() == 1) {
            result.add(new Routed(Map.of(IotWatchDecodeService.DATA_DECODED_KEY, tagged.get(0)), targetNames));
        }
        // tagged.size() > 1 → ambiguous mis-tagged payload → consume none of the tagged readings
        return result;
    }
}
