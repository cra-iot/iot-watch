package org.openremote.manager.iotwatch;

import org.openremote.model.asset.Asset;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.geo.GeoJSONPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps LoRaWAN GNSS fields to a location point and battery percentage. location is the
 * inherited base-Asset attribute; battery is declared on the tracker classes.
 */
public class GnssDecoder implements DeviceDecoder {

    static final String LOCATION = "location";
    static final String BATTERY = "battery";

    @Override
    public Set<String> candidateAttributeNames(Asset<?> asset) {
        return Set.of(LOCATION, BATTERY);
    }

    @Override
    public List<AttributeEvent> decode(String assetId, Map<String, Object> message,
                                       Set<String> targetNames, long timestamp) {
        Object decodedObj = message.get("data_decoded");
        if (!(decodedObj instanceof Map)) {
            return List.of();
        }
        Map<?, ?> decoded = (Map<?, ?>) decodedObj;
        List<AttributeEvent> events = new ArrayList<>();

        Object lat = decoded.get("gnss_latitude");
        Object lon = decoded.get("gnss_longitude");
        if (targetNames.contains(LOCATION) && lat instanceof Number && lon instanceof Number) {
            events.add(new AttributeEvent(assetId, LOCATION,
                new GeoJSONPoint(((Number) lon).doubleValue(), ((Number) lat).doubleValue()), timestamp));
        }

        Object battery = decoded.get("battery_pct");
        if (targetNames.contains(BATTERY) && battery instanceof Number) {
            events.add(new AttributeEvent(assetId, BATTERY, ((Number) battery).doubleValue(), timestamp));
        }

        return events;
    }
}
