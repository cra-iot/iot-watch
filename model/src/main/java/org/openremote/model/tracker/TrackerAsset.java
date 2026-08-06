package org.openremote.model.tracker;

import jakarta.persistence.Entity;

import java.util.Optional;

import static org.openremote.model.Constants.*;

import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetDescriptor;
import org.openremote.model.attribute.MetaItem;
import org.openremote.model.value.AttributeDescriptor;
import org.openremote.model.value.MetaItemType;
import org.openremote.model.value.ValueType;

@Entity
public class TrackerAsset extends Asset<TrackerAsset> {

    // ── Device telemetry (permanent, written by Groovy rule) ─────────────────

    // Platform device identifier (LoRa devEUI), matched by the HTTP ingest endpoint
    public static final AttributeDescriptor<String> DEV_EUI_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("devEui", ValueType.TEXT,
                    new MetaItem<>(MetaItemType.LABEL, "Identifikátor zařízení (devEUI)"));

    public static final AttributeDescriptor<ValueType.ObjectMap> RAW_VALUE_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("rawValue", ValueType.JSON_OBJECT,
                    new MetaItem<>(MetaItemType.LABEL, "Surová data"),
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.RULE_STATE));

    public static final AttributeDescriptor<Double> BATTERY_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("battery", ValueType.NUMBER,
                    new MetaItem<>(MetaItemType.LABEL, "Stav baterie"),
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.RULE_STATE),
                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                    .withUnits(UNITS_PERCENTAGE);

    // ── Assignment (filled when the tracker is put in use, cleared when idle) ──

    public static final AttributeDescriptor<Boolean> ACTIVE_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("active", ValueType.BOOLEAN,
                    new MetaItem<>(MetaItemType.LABEL, "Aktivní"),
                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                    .withOptional(true);

    public static final AttributeDescriptor<String> ASSIGNED_TO_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("assignedTo", ValueType.TEXT,
                    new MetaItem<>(MetaItemType.LABEL, "Přiřazený k"),
                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                    .withOptional(true);

    // ── Asset descriptor (icon from OR icon set, teal colour) ─────────────────

    public static final AssetDescriptor<TrackerAsset> TRACKER_ASSET_DESCRIPTOR =
            new AssetDescriptor<>("map-marker", "009688", TrackerAsset.class);

    protected TrackerAsset() {
    }

    public TrackerAsset(String name) {
        super(name);
    }

    // ── Device telemetry getters ──────────────────────────────────────────────

    public Optional<String> getDevEui() {
        return getAttributes().getValue(DEV_EUI_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<ValueType.ObjectMap> getRawValue() {
        return getAttributes().getValue(RAW_VALUE_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<Double> getBattery() {
        return getAttributes().getValue(BATTERY_ATTRIBUTE_DESCRIPTOR);
    }

    // ── Assignment getters/setters ────────────────────────────────────────────

    public Optional<Boolean> isActive() {
        return getAttributes().getValue(ACTIVE_ATTRIBUTE_DESCRIPTOR);
    }

    public TrackerAsset setActive(boolean active) {
        getAttributes().getOrCreate(ACTIVE_ATTRIBUTE_DESCRIPTOR).setValue(active);
        return this;
    }

    public Optional<String> getAssignedTo() {
        return getAttributes().getValue(ASSIGNED_TO_ATTRIBUTE_DESCRIPTOR);
    }

    public TrackerAsset setAssignedTo(String assignedTo) {
        getAttributes().getOrCreate(ASSIGNED_TO_ATTRIBUTE_DESCRIPTOR).setValue(assignedTo);
        return this;
    }
}
