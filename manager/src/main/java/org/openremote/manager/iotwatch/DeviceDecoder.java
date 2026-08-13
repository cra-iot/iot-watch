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
}
