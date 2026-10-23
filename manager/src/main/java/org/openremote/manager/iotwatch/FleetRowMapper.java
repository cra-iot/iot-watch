package org.openremote.manager.iotwatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.openremote.model.asset.Asset;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.iotwatch.FleetColumns;
import org.openremote.model.iotwatch.FleetRow;

/**
 * Projects a loaded asset onto the compact row the overview renders.
 *
 * lastSeen and lastReading are deliberately different values: rawValue is written on every
 * ingest even when decoding fails, so a fresh lastSeen with a stale lastReading identifies a
 * decode problem rather than a radio problem.
 */
public final class FleetRowMapper {

    private static final String RAW_VALUE = FleetColumns.RAW_VALUE;

    private FleetRowMapper() {
    }

    /**
     * @param columns  attribute names to carry in {@link FleetRow#values}, as resolved by
     *                 {@link FleetColumnResolver#columnsFor}. A name the asset does not carry is
     *                 simply absent from the map - the UI renders an em dash either way.
     * @param lastSeen rawValue's timestamp from FleetResourceImpl's server-side lookup, or null.
     *                 Not read from the asset: rawValue is never in the authorized select.
     */
    public static FleetRow map(Asset<?> asset, FleetColumnResolver resolver, Collection<String> columns,
                               Long lastSeen) {
        FleetRow row = new FleetRow();
        row.id = asset.getId();
        row.name = asset.getName();
        row.type = asset.getType();
        row.parentId = asset.getParentId();
        if (asset.getPath() != null) {
            row.path = new ArrayList<>(Arrays.asList(asset.getPath()));
        }

        row.deliveryPointId = text(asset, "delivery_point_id");
        row.meterId = text(asset, "meter_id");
        row.devEui = text(asset, "devEui");
        row.externalId = text(asset, "external_id");

        row.primaryName = resolver.primaryRegister(asset.getType());
        if (row.primaryName != null) {
            // getTimestamp() is Optional<Long>: empty when the attribute never received a value.
            row.lastReading = attribute(asset, row.primaryName)
                    .flatMap(Attribute::getTimestamp)
                    .orElse(null);
        }

        // Only the requested columns, and never rawValue: its value is large and device-private.
        if (columns != null) {
            for (String name : columns) {
                if (RAW_VALUE.equals(name)) {
                    continue;
                }
                attribute(asset, name).ifPresent(attribute ->
                        row.values.put(name, attribute.getValue().orElse(null)));
            }
        }

        row.batteryLevel = attribute(asset, "battery_level")
                .flatMap(Attribute::getValue)
                .filter(Number.class::isInstance)
                .map(value -> ((Number) value).intValue())
                .orElse(null);

        row.lastSeen = lastSeen;

        List<String> alarms = new ArrayList<>();
        asset.getAttributes().values().forEach(attribute -> {
            if (RAW_VALUE.equals(attribute.getName())
                    || FleetColumns.STATE_BOOLEANS.contains(attribute.getName())) {
                return;
            }
            attribute.getValue()
                    .filter(Boolean.class::isInstance)
                    .map(Boolean.class::cast)
                    .filter(Boolean::booleanValue)
                    .ifPresent(ignored -> alarms.add(attribute.getName()));
        });
        row.alarms = alarms;

        return row;
    }

    private static Optional<Attribute<?>> attribute(Asset<?> asset, String name) {
        return asset.getAttributes().get(name).map(attribute -> (Attribute<?>) attribute);
    }

    private static String text(Asset<?> asset, String name) {
        return attribute(asset, name)
                .flatMap(Attribute::getValue)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .orElse(null);
    }
}
