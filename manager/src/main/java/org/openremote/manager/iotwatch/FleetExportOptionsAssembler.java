package org.openremote.manager.iotwatch;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.openremote.model.iotwatch.FleetExportOptions;

/** Builds the export dialog's options from the matched devices per type; no I/O. */
public final class FleetExportOptionsAssembler {

    private FleetExportOptionsAssembler() {
    }

    /**
     * Types sorted by name; a type without register groups the caller can read is left out, since
     * its rows would carry no reading. The last allowed day and month follow now in Prague.
     */
    public static FleetExportOptions assemble(Map<String, Integer> deviceCountsByType, boolean restricted,
                                              Instant now) {
        FleetExportOptions options = new FleetExportOptions();
        options.lastDay = FleetExportPeriod.lastDay(now).toString();
        options.lastMonth = FleetExportPeriod.lastMonth(now).toString();
        options.maxDays = FleetExportPeriod.MAX_DAYS;
        options.maxMonths = FleetExportPeriod.MAX_MONTHS;
        new TreeMap<>(deviceCountsByType).forEach((assetType, count) -> {
            List<FleetExportRegisters.Group> groups = FleetExportRegisters.groups(assetType, restricted);
            if (groups.isEmpty()) {
                return;
            }
            FleetExportOptions.Type type = new FleetExportOptions.Type();
            type.type = assetType;
            type.deviceCount = count == null ? 0 : count;
            groups.forEach(group -> {
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
}
