package org.openremote.manager.iotwatch;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

import org.openremote.model.attribute.MetaItem;
import org.openremote.model.iotwatch.FleetExportOptions;
import org.openremote.model.value.AttributeDescriptor;
import org.openremote.model.value.MetaItemType;

/** Builds the export dialog's options from the matched devices per type; no I/O. */
public final class FleetExportOptionsAssembler {

    private FleetExportOptionsAssembler() {
    }

    /**
     * Types sorted by name; a type without exportable measurements for the caller is left out,
     * since its rows would carry no daily value. Identifiers and the primary register are
     * selected by default.
     */
    public static FleetExportOptions assemble(Map<String, Integer> deviceCountsByType, boolean restricted,
                                              FleetColumnResolver resolver, LocalDate maxTo) {
        FleetExportOptions options = new FleetExportOptions();
        options.maxTo = maxTo.toString();
        options.maxDays = FleetExportRange.MAX_DAYS;
        new TreeMap<>(deviceCountsByType).forEach((assetType, count) -> {
            List<AttributeDescriptor<?>> measurements = FleetExportAttributes.measurements(assetType, restricted);
            if (measurements.isEmpty()) {
                return;
            }
            String primary = resolver.primaryRegister(assetType);
            FleetExportOptions.Type type = new FleetExportOptions.Type();
            type.type = assetType;
            type.deviceCount = count == null ? 0 : count;
            FleetExportAttributes.identity(assetType, restricted).forEach(descriptor -> type.identity.add(
                    attribute(descriptor, FleetExportAttributes.DEFAULT_IDENTITY::contains)));
            measurements.forEach(descriptor -> type.measurements.add(
                    attribute(descriptor, name -> name.equals(primary))));
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
}
