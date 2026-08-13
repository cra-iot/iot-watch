package org.openremote.manager.iotwatch;

import org.openremote.model.asset.Asset;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.value.AttributeDescriptor;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Copies each data_decoded entry into the asset attribute of the same name. Candidate names
 * are the attribute descriptors declared by the asset's own class (getDeclaredFields excludes
 * inherited base-Asset attributes such as name/location/notes), minus the identity/raw
 * attributes, so a decoded key can never clobber those.
 */
public class SameNameDecoder implements DeviceDecoder {

    // "rawValue" is also exposed as IotWatchDecodeService.RAW_VALUE_ATTRIBUTE_NAME (Task 4);
    // kept as a literal here to avoid a forward dependency on that class.
    private static final Set<String> EXCLUDED = Set.of("rawValue", "devEui", "external_id");

    @Override
    public Set<String> candidateAttributeNames(Asset<?> asset) {
        return declaredAttributeNames(asset.getClass());
    }

    @Override
    public List<AttributeEvent> decode(String assetId, Map<String, Object> message,
                                       Set<String> targetNames, long timestamp) {
        Map<?, ?> decoded = DeviceDecoder.successfulDecodedPayload(message);
        if (decoded == null) {
            return List.of();
        }
        List<AttributeEvent> events = new ArrayList<>();
        for (Map.Entry<?, ?> entry : decoded.entrySet()) {
            String name = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (value != null && targetNames.contains(name)) {
                events.add(new AttributeEvent(assetId, name, value, timestamp));
            }
        }
        return events;
    }

    static Set<String> declaredAttributeNames(Class<?> assetClass) {
        Set<String> names = new HashSet<>();
        for (Field field : assetClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                && AttributeDescriptor.class.isAssignableFrom(field.getType())) {
                try {
                    String name = ((AttributeDescriptor<?>) field.get(null)).getName();
                    if (!EXCLUDED.contains(name)) {
                        names.add(name);
                    }
                } catch (IllegalAccessException ignored) {
                    // public static fields are accessible; ignore defensively
                }
            }
        }
        return names;
    }
}
