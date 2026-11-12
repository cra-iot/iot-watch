package org.openremote.manager.iotwatch;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Predicate;

import org.openremote.model.attribute.MetaItem;
import org.openremote.model.iotwatch.FleetColumns;
import org.openremote.model.iotwatch.FleetExportOptions;
import org.openremote.model.util.ValueUtil;
import org.openremote.model.value.AttributeDescriptor;
import org.openremote.model.value.MetaItemType;

/** Builds the export dialog's options from the matched devices per type; no I/O. */
public final class FleetExportOptionsAssembler {

    private FleetExportOptionsAssembler() {
    }

    /**
     * Types sorted by name; a type without register groups the caller can read is left out, since
     * its rows would carry no reading. Identity and the primary register are selected by default.
     */
    public static FleetExportOptions assemble(Map<String, Integer> deviceCountsByType, boolean restricted,
                                              FleetColumnResolver resolver, LocalDate maxTo) {
        FleetExportOptions options = new FleetExportOptions();
        options.maxTo = maxTo.toString();
        options.maxDays = FleetExportPeriod.MAX_DAYS;
        new TreeMap<>(deviceCountsByType).forEach((assetType, count) -> {
            List<FleetExportRegisters.Group> groups = FleetExportRegisters.groups(assetType, restricted);
            if (groups.isEmpty()) {
                return;
            }
            Map<String, AttributeDescriptor<?>> descriptors = ValueUtil.getAssetInfo(assetType)
                    .map(info -> info.getAttributeDescriptors())
                    .orElse(Map.of());
            String primary = resolver.primaryRegister(assetType);
            FleetExportOptions.Type type = new FleetExportOptions.Type();
            type.type = assetType;
            type.deviceCount = count == null ? 0 : count;
            FleetColumns.EXPORT_IDENTITY.stream()
                    .map(descriptors::get)
                    .filter(Objects::nonNull)
                    .filter(descriptor -> !restricted || isRestrictedReadable(descriptor))
                    .forEach(descriptor -> type.identity.add(attribute(descriptor, name -> true)));
            groups.forEach(group -> {
                type.measurements.add(attribute(descriptors.get(group.attribute()), name -> name.equals(primary)));
                FleetExportOptions.Group option = new FleetExportOptions.Group();
                option.key = group.key();
                option.attribute = group.attribute();
                option.label = group.label();
                option.unit = group.unit();
                type.groups.add(option);
            });
            options.types.add(type);
        });
        return options;
    }

    private static FleetExportOptions.Attribute attribute(AttributeDescriptor<?> descriptor,
                                                          Predicate<String> defaultSelected) {
        FleetExportOptions.Attribute attribute = new FleetExportOptions.Attribute();
        attribute.name = descriptor.getName();
        attribute.label = descriptor.getMeta() == null ? descriptor.getName()
                : descriptor.getMeta().get(MetaItemType.LABEL)
                        .flatMap(MetaItem::getValue)
                        .orElse(descriptor.getName());
        attribute.defaultSelected = defaultSelected.test(attribute.name);
        return attribute;
    }

    private static boolean isRestrictedReadable(AttributeDescriptor<?> descriptor) {
        return descriptor.getMeta() != null
                && descriptor.getMeta().get(MetaItemType.ACCESS_RESTRICTED_READ)
                        .flatMap(MetaItem::getValue)
                        .orElse(false);
    }
}
