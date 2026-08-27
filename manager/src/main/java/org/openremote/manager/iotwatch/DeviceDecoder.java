package org.openremote.manager.iotwatch;

import org.openremote.model.asset.Asset;
import org.openremote.model.attribute.AttributeEvent;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a device's rawValue message (its platform-decoded data_decoded map) into typed
 * attribute events for one asset type. Implementations are stateless and shared.
 */
public interface DeviceDecoder {

    /**
     * Attribute names this decoder may write for the given asset, before intersecting with
     * the attributes the instance actually has.
     */
    Set<String> candidateAttributeNames(Asset<?> asset);

    /**
     * Emit one event per decodable field whose target attribute name is in {@code targetNames}.
     */
    List<AttributeEvent> decode(String assetId, Map<String, Object> message,
                                Set<String> targetNames, long timestamp);

    /**
     * The decoded payload to fan out, or {@code null} when there is nothing to decode:
     * {@code data_decoded} is absent, null, or not a map, or the platform signalled a
     * failed decode via {@code decoded == false} or {@code ok == false} (in which case
     * no measurement data is valid and nothing must be written).
     */
    static Map<?, ?> successfulDecodedPayload(Map<String, Object> message) {
        Object dd = message.get("data_decoded");
        if (!(dd instanceof Map)) {
            return null;
        }
        Map<?, ?> decoded = (Map<?, ?>) dd;
        if (isFailedDecode(decoded)) {
            return null;
        }
        return decoded;
    }

    /**
     * The platform's failed-decode signal on an unwrapped {@code data_decoded} payload:
     * {@code decoded == false} or {@code ok == false}. Exposed separately from
     * {@link #successfulDecodedPayload} because {@link ReadingRouter} must apply it to a batch
     * envelope, whose flags are left behind when each reading is re-wrapped on its own.
     */
    static boolean isFailedDecode(Map<?, ?> decoded) {
        return Boolean.FALSE.equals(decoded.get("decoded")) || Boolean.FALSE.equals(decoded.get("ok"));
    }
}
