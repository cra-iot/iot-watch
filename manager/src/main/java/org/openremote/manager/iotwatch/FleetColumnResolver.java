package org.openremote.manager.iotwatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openremote.model.Container;
import org.openremote.model.asset.AssetDescriptor;
import org.openremote.model.asset.AssetTypeInfo;
import org.openremote.model.asset.agent.AgentDescriptor;
import org.openremote.model.attribute.MetaItem;
import org.openremote.model.iotwatch.FleetColumns;
import org.openremote.model.iotwatch.FleetQuery;
import org.openremote.model.iotwatch.FleetQueryResult.ColumnDescriptor;
import org.openremote.model.util.ValueUtil;
import org.openremote.model.value.AttributeDescriptor;
import org.openremote.model.value.MetaItemType;

/**
 * Answers "which attributes does a row of this type carry" from the asset model rather than from
 * a hardcoded list, so a type nobody anticipated still renders.
 *
 * The primary register resolves in four steps: the OR_IOTWATCH_FLEET_PRIMARY_REGISTER override,
 * so a wrong answer is fixable without a release; FleetColumns.PRIMARY_REGISTERS, declared in
 * model/; the first numeric descriptor carrying STORE_DATA_POINTS and units; then none, and the
 * row simply has no primary value.
 *
 * Step 3 depends on the order AssetTypeInfo.getAttributeDescriptors() iterates in, which
 * reflection does not guarantee. That is why a declared type never reaches it, and why the UI
 * lets the user pick columns: a guess the model cannot make deterministically is the user's to
 * correct, not something to encode as a second hardcoded table.
 */
public class FleetColumnResolver {

    public static final String PRIMARY_REGISTER_OVERRIDE = "OR_IOTWATCH_FLEET_PRIMARY_REGISTER";

    private final Map<String, String> overrides;

    public FleetColumnResolver(Map<String, String> overrides) {
        this.overrides = overrides == null ? Collections.emptyMap() : overrides;
    }

    /** Parses "GasMeterAsset:volume,ThermometerAsset:temperature"; absent or blank means none. */
    public static FleetColumnResolver fromConfig(Container container) {
        String raw = container.getConfig().get(PRIMARY_REGISTER_OVERRIDE);
        Map<String, String> overrides = new LinkedHashMap<>();
        if (raw != null && !raw.isBlank()) {
            for (String entry : raw.split(",")) {
                String[] parts = entry.split(":", 2);
                if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                    overrides.put(parts[0].trim(), parts[1].trim());
                }
            }
        }
        return new FleetColumnResolver(overrides);
    }

    public String primaryRegister(String assetType) {
        String override = overrides.get(assetType);
        if (override != null) {
            return override;
        }
        String declared = FleetColumns.PRIMARY_REGISTERS.get(assetType);
        if (declared != null) {
            return declared;
        }
        return ValueUtil.getAssetInfo(assetType)
                .map(info -> firstMeasurement(info.getAttributeDescriptors().values()))
                .orElse(null);
    }

    /**
     * The first descriptor that looks like a measurement register: numeric, carrying units and
     * marked for datapoint storage. Identity attributes have no units, and battery_level
     * deliberately carries no STORE_DATA_POINTS, so both fall out without being named here.
     */
    static String firstMeasurement(Collection<AttributeDescriptor<?>> descriptors) {
        return descriptors.stream()
                .filter(FleetColumnResolver::isMeasurement)
                .map(AttributeDescriptor::getName)
                .findFirst()
                .orElse(null);
    }

    /** Every attribute a row of these types needs loaded; the AssetQuery select. */
    public String[] selectAttributes(FleetQuery query, Collection<String> types) {
        Set<String> names = new LinkedHashSet<>(Arrays.asList(FleetColumns.INFRASTRUCTURE_ATTRIBUTES));
        for (String type : typesOrAll(types)) {
            ValueUtil.getAssetInfo(type).ifPresent(info ->
                    info.getAttributeDescriptors().values().stream()
                            .filter(FleetColumnResolver::isAlarm)
                            .map(AttributeDescriptor::getName)
                            .forEach(names::add));
            String primary = primaryRegister(type);
            if (primary != null) {
                names.add(primary);
            }
        }
        if (query.columns != null) {
            names.addAll(query.columns);
        }
        // Defence in depth: a caller-requested "rawValue" column must not load the payload.
        names.remove(FleetColumns.RAW_VALUE);
        return names.toArray(new String[0]);
    }

    /**
     * Every column these types offer: the selected ones first, then the rest for the picker.
     * Pass the types actually present in the result, not the query's type filter: an empty
     * filter means "whole realm", and resolving over every registered type would offer the
     * columns of OpenRemote built-ins nobody has.
     */
    public List<ColumnDescriptor> columnsFor(Collection<String> types, List<String> requested) {
        if (types == null || types.isEmpty()) {
            return new ArrayList<>();
        }
        List<ColumnDescriptor> columns = new ArrayList<>();
        for (String type : types) {
            String primary = primaryRegister(type);
            ValueUtil.getAssetInfo(type).ifPresent(info ->
                    info.getAttributeDescriptors().values().stream()
                            .filter(FleetColumnResolver::isMeasurement)
                            .forEach(descriptor -> {
                                boolean isPrimary = descriptor.getName().equals(primary);
                                boolean selected = isPrimary
                                        || (requested != null && requested.contains(descriptor.getName()));
                                columns.add(describe(descriptor, type, isPrimary, selected));
                            }));
        }
        columns.sort(Comparator
                .comparing((ColumnDescriptor column) -> !column.selected)
                .thenComparing(column -> !column.primary)
                .thenComparing(column -> column.label == null ? column.name : column.label));
        return columns;
    }

    private Collection<String> typesOrAll(Collection<String> types) {
        if (types != null && !types.isEmpty()) {
            return types;
        }
        // An empty type filter means the whole realm, so the types are not known before the
        // query runs. The registered model is finite, so resolving the select over all of it is
        // bounded. Agents are not fleet devices and are skipped.
        return Arrays.stream(ValueUtil.getAssetDescriptors(null))
                .filter(descriptor -> !(descriptor instanceof AgentDescriptor))
                .map(AssetDescriptor::getName)
                .toList();
    }

    private static ColumnDescriptor describe(AttributeDescriptor<?> descriptor, String assetType,
                                             boolean primary, boolean selected) {
        ColumnDescriptor column = new ColumnDescriptor();
        column.name = descriptor.getName();
        column.label = descriptor.getMeta() == null ? null
                : descriptor.getMeta().get(MetaItemType.LABEL)
                        .flatMap(MetaItem::getValue)
                        .orElse(descriptor.getName());
        column.units = descriptor.getUnits();
        column.numeric = isNumeric(descriptor);
        column.primary = primary;
        column.selected = selected;
        column.assetType = assetType;
        return column;
    }

    private static boolean isMeasurement(AttributeDescriptor<?> descriptor) {
        return isNumeric(descriptor)
                && descriptor.getUnits() != null && descriptor.getUnits().length > 0
                && descriptor.getMeta() != null
                && descriptor.getMeta().get(MetaItemType.STORE_DATA_POINTS).isPresent();
    }

    private static boolean isNumeric(AttributeDescriptor<?> descriptor) {
        return descriptor.getType() != null
                && Number.class.isAssignableFrom(descriptor.getType().getType());
    }

    /** A boolean that flags a fault; state booleans such as valve_closed are not alarms. */
    static boolean isAlarm(AttributeDescriptor<?> descriptor) {
        return descriptor.getType() != null
                && Boolean.class.isAssignableFrom(descriptor.getType().getType())
                && !FleetColumns.STATE_BOOLEANS.contains(descriptor.getName());
    }
}
