package org.openremote.manager.iotwatch;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openremote.model.asset.AssetTypeInfo;
import org.openremote.model.attribute.MetaItem;
import org.openremote.model.iotwatch.FleetColumns;
import org.openremote.model.util.ValueUtil;
import org.openremote.model.value.AttributeDescriptor;
import org.openremote.model.value.MetaItemType;

/**
 * Which declared attributes of an asset type the fleet export may offer.
 *
 * Only attributes with stored history can give a value for a past day, so measurements are the
 * declared STORE_DATA_POINTS scalars; live state without history (battery_level on a water
 * meter) is never offered. Identity attributes export their current value and are a fixed list.
 * A restricted user can read only descriptors carrying ACCESS_RESTRICTED_READ. Only type
 * descriptors are consulted, so hand-added attributes never appear.
 */
public final class FleetExportAttributes {

    public static final List<String> IDENTITY = List.of("devEui", "meter_id", "external_id", "delivery_point_id",
            "notes", "model", "manufacturer", "email", "tags");

    /** Identity selected by default in the dialog. */
    public static final Set<String> DEFAULT_IDENTITY = Set.of("devEui", "meter_id", "external_id", "delivery_point_id");

    private FleetExportAttributes() {
    }

    /** Declared identity descriptors of the type, in IDENTITY order; restricted: only ACCESS_RESTRICTED_READ ones. */
    public static List<AttributeDescriptor<?>> identity(String assetType, boolean restricted) {
        return ValueUtil.getAssetInfo(assetType)
                .map(info -> IDENTITY.stream()
                        .map(name -> info.getAttributeDescriptors().get(name))
                        .filter(descriptor -> descriptor != null)
                        .filter(descriptor -> !restricted || isRestrictedReadable(descriptor))
                        .<AttributeDescriptor<?>>map(descriptor -> descriptor)
                        .toList())
                .orElse(List.of());
    }

    /**
     * Declared STORE_DATA_POINTS scalar descriptors except rawValue, in declaration order (see
     * declaredOrder); restricted as above.
     */
    public static List<AttributeDescriptor<?>> measurements(String assetType, boolean restricted) {
        return ValueUtil.getAssetInfo(assetType)
                .map(info -> declaredOrder(info).stream()
                        .filter(descriptor -> !FleetColumns.RAW_VALUE.equals(descriptor.getName()))
                        .filter(FleetExportAttributes::isScalar)
                        .filter(FleetExportAttributes::storesDataPoints)
                        .filter(descriptor -> !restricted || isRestrictedReadable(descriptor))
                        .toList())
                .orElse(List.of());
    }

    /**
     * The type's attribute descriptors in the declaration order of the static AttributeDescriptor
     * fields, from the concrete asset class up through its superclasses; descriptors without such a
     * field follow in map order. getAttributeDescriptors() itself is a HashMap with no stable order.
     */
    static List<AttributeDescriptor<?>> declaredOrder(AssetTypeInfo info) {
        Map<String, AttributeDescriptor<?>> descriptors = info.getAttributeDescriptors();
        Set<String> names = new LinkedHashSet<>();
        Class<?> type = info.getAssetDescriptor() == null ? null : info.getAssetDescriptor().getType();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        && AttributeDescriptor.class.isAssignableFrom(field.getType())) {
                    try {
                        Object value = field.get(null);
                        if (value instanceof AttributeDescriptor<?> descriptor) {
                            names.add(descriptor.getName());
                        }
                    } catch (IllegalAccessException | RuntimeException ignored) {
                        // Unreadable field: its descriptor still follows in map order below.
                    }
                }
            }
        }
        names.addAll(descriptors.keySet());
        List<AttributeDescriptor<?>> ordered = new ArrayList<>();
        for (String name : names) {
            AttributeDescriptor<?> descriptor = descriptors.get(name);
            if (descriptor != null) {
                ordered.add(descriptor);
            }
        }
        return ordered;
    }

    /** True when measurements(assetType, false) is not empty. */
    public static boolean exportable(String assetType) {
        return !measurements(assetType, false).isEmpty();
    }

    private static boolean isScalar(AttributeDescriptor<?> descriptor) {
        if (descriptor.getType() == null) {
            return false;
        }
        Class<?> type = descriptor.getType().getType();
        return type != null && !type.isArray()
                && (Number.class.isAssignableFrom(type) || String.class.isAssignableFrom(type)
                        || Boolean.class.isAssignableFrom(type));
    }

    private static boolean storesDataPoints(AttributeDescriptor<?> descriptor) {
        return hasEnabledFlag(descriptor, MetaItemType.STORE_DATA_POINTS);
    }

    private static boolean isRestrictedReadable(AttributeDescriptor<?> descriptor) {
        return hasEnabledFlag(descriptor, MetaItemType.ACCESS_RESTRICTED_READ);
    }

    private static boolean hasEnabledFlag(AttributeDescriptor<?> descriptor,
            org.openremote.model.value.MetaItemDescriptor<Boolean> metaType) {
        return descriptor.getMeta() != null
                && descriptor.getMeta().get(metaType)
                        .flatMap(MetaItem::getValue)
                        .orElse(false);
    }
}
