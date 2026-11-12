package org.openremote.manager.iotwatch;

import java.util.List;
import java.util.Set;

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

    /** Declared STORE_DATA_POINTS scalar descriptors except rawValue, in getAttributeDescriptors() order; restricted as above. */
    public static List<AttributeDescriptor<?>> measurements(String assetType, boolean restricted) {
        return ValueUtil.getAssetInfo(assetType)
                .map(info -> info.getAttributeDescriptors().values().stream()
                        .filter(descriptor -> !FleetColumns.RAW_VALUE.equals(descriptor.getName()))
                        .filter(FleetExportAttributes::isScalar)
                        .filter(FleetExportAttributes::storesDataPoints)
                        .filter(descriptor -> !restricted || isRestrictedReadable(descriptor))
                        .<AttributeDescriptor<?>>map(descriptor -> descriptor)
                        .toList())
                .orElse(List.of());
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
