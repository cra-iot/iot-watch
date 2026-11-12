package org.openremote.manager.iotwatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.openremote.model.Constants;
import org.openremote.model.attribute.MetaItem;
import org.openremote.model.iotwatch.FleetColumns;
import org.openremote.model.util.ValueUtil;
import org.openremote.model.value.AttributeDescriptor;
import org.openremote.model.value.MetaItemDescriptor;
import org.openremote.model.value.MetaItemType;

/**
 * The cumulative registers a report offers per meter type, as column groups.
 *
 * Which attributes form the groups is the fixed table FleetColumns.EXPORT_REGISTERS; this class
 * resolves them against the type's descriptors, so label, unit and restricted access come from
 * the model. A group whose attribute is not declared on the type is dropped.
 */
public final class FleetExportRegisters {

    /** One register: key is the column prefix, label the descriptor label, unit the short unit symbol. */
    public record Group(String key, String attribute, String label, String unit) {
    }

    private static final Map<String, String> UNIT_SYMBOLS = Map.of(
            Constants.UNITS_KILO, "k",
            Constants.UNITS_MEGA, "M",
            Constants.UNITS_GIGA, "G",
            Constants.UNITS_WATT, "W",
            Constants.UNITS_HOUR, "h",
            Constants.UNITS_JOULE, "J",
            Constants.UNITS_METRE, "m",
            Constants.UNITS_CUBED, "3",
            Constants.UNITS_LITRE, "l",
            Constants.UNITS_PASCAL, "Pa");

    private FleetExportRegisters() {
    }

    /** Register groups of the type in table order; restricted: only ACCESS_RESTRICTED_READ ones. */
    public static List<Group> groups(String assetType, boolean restricted) {
        Map<String, String> table = FleetColumns.EXPORT_REGISTERS.get(assetType);
        if (table == null) {
            return List.of();
        }
        return ValueUtil.getAssetInfo(assetType)
                .map(info -> {
                    List<Group> groups = new ArrayList<>();
                    table.forEach((key, attribute) -> {
                        AttributeDescriptor<?> descriptor = info.getAttributeDescriptors().get(attribute);
                        if (descriptor != null && (!restricted || isRestrictedReadable(descriptor))) {
                            groups.add(new Group(key, attribute, label(descriptor), unit(descriptor)));
                        }
                    });
                    return List.copyOf(groups);
                })
                .orElse(List.of());
    }

    /** The descriptor's units joined as one symbol, e.g. {kilo, watt, hour} gives kWh; empty without units. */
    public static String unit(AttributeDescriptor<?> descriptor) {
        String[] units = descriptor.getUnits();
        if (units == null) {
            return "";
        }
        StringBuilder symbol = new StringBuilder();
        for (String token : units) {
            symbol.append(UNIT_SYMBOLS.getOrDefault(token, token));
        }
        return symbol.toString();
    }

    /** Column header of a group part (start, end or consumption), with the unit suffix when there is one. */
    public static String header(Group group, String part) {
        return group.key() + "_" + part + (group.unit().isEmpty() ? "" : "_" + group.unit());
    }

    /** True when the type has at least one register group. */
    public static boolean exportable(String assetType) {
        return !groups(assetType, false).isEmpty();
    }

    private static String label(AttributeDescriptor<?> descriptor) {
        return descriptor.getMeta() == null ? descriptor.getName()
                : descriptor.getMeta().get(MetaItemType.LABEL)
                        .flatMap(MetaItem::getValue)
                        .orElse(descriptor.getName());
    }

    private static boolean isRestrictedReadable(AttributeDescriptor<?> descriptor) {
        MetaItemDescriptor<Boolean> flag = MetaItemType.ACCESS_RESTRICTED_READ;
        return descriptor.getMeta() != null
                && descriptor.getMeta().get(flag)
                        .flatMap(MetaItem::getValue)
                        .orElse(false);
    }
}
