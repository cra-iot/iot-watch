package org.openremote.model.watermeter;

import jakarta.persistence.Entity;

import java.util.Optional;

import org.openremote.model.Constants;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetDescriptor;
import org.openremote.model.attribute.MetaItem;
import org.openremote.model.value.AttributeDescriptor;
import org.openremote.model.value.MetaItemType;
import org.openremote.model.value.ValueType;

@Entity
public class WaterMeterAsset extends Asset<WaterMeterAsset> {

    // Platform device identifier (LoRa devEUI), matched by the HTTP ingest endpoint
    public static final AttributeDescriptor<String> DEV_EUI_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("devEui", ValueType.TEXT,
                    new MetaItem<>(MetaItemType.LABEL, "Identifikátor zařízení (devEUI)"));

    public static final AttributeDescriptor<ValueType.ObjectMap> RAW_VALUE_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("rawValue", ValueType.JSON_OBJECT,
                    new MetaItem<>(MetaItemType.LABEL, "Surová data"),
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.RULE_STATE));

    public static final AttributeDescriptor<Double> CURRENT_READING_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("currentReading", ValueType.NUMBER,
                    new MetaItem<>(MetaItemType.LABEL, "Aktuální stav vodoměru"),
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.STORE_DATA_POINTS),
                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                    .withUnits(Constants.UNITS_METRE, Constants.UNITS_CUBED);

    // Alarm/error flags decoded from rawValue by the Manager-UI Groovy rules.
    // See docs/superpowers/specs/2026-08-05-watermeter-alarms-design.md for the
    // payload bit -> attribute mapping.

    public static final AttributeDescriptor<Boolean> LEAKAGE_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("leakage", ValueType.BOOLEAN,
                    new MetaItem<>(MetaItemType.LABEL, "Únik vody"),
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.RULE_STATE),
                    new MetaItem<>(MetaItemType.STORE_DATA_POINTS),
                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                    .withOptional(true);

    public static final AttributeDescriptor<Boolean> BURST_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("burst", ValueType.BOOLEAN,
                    new MetaItem<>(MetaItemType.LABEL, "Prasklé potrubí"),
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.RULE_STATE),
                    new MetaItem<>(MetaItemType.STORE_DATA_POINTS),
                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                    .withOptional(true);

    public static final AttributeDescriptor<Boolean> OVERFLOW_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("overflow", ValueType.BOOLEAN,
                    new MetaItem<>(MetaItemType.LABEL, "Přetečení"),
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.RULE_STATE),
                    new MetaItem<>(MetaItemType.STORE_DATA_POINTS),
                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                    .withOptional(true);

    public static final AttributeDescriptor<Boolean> INVERSE_FLOW_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("inverseFlow", ValueType.BOOLEAN,
                    new MetaItem<>(MetaItemType.LABEL, "Zpětný tok"),
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.RULE_STATE),
                    new MetaItem<>(MetaItemType.STORE_DATA_POINTS),
                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                    .withOptional(true);

    public static final AttributeDescriptor<Boolean> TAMPER_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("tamper", ValueType.BOOLEAN,
                    new MetaItem<>(MetaItemType.LABEL, "Neoprávněná manipulace"),
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.RULE_STATE),
                    new MetaItem<>(MetaItemType.STORE_DATA_POINTS),
                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                    .withOptional(true);

    public static final AttributeDescriptor<Boolean> REVERSE_INSTALLATION_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("reverseInstallation", ValueType.BOOLEAN,
                    new MetaItem<>(MetaItemType.LABEL, "Opačná instalace"),
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.RULE_STATE),
                    new MetaItem<>(MetaItemType.STORE_DATA_POINTS),
                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                    .withOptional(true);

    public static final AttributeDescriptor<Boolean> LOW_BATTERY_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("lowBattery", ValueType.BOOLEAN,
                    new MetaItem<>(MetaItemType.LABEL, "Slabá baterie"),
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.RULE_STATE),
                    new MetaItem<>(MetaItemType.STORE_DATA_POINTS),
                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                    .withOptional(true);

    public static final AttributeDescriptor<Boolean> MEASURE_BOARD_ERROR_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("measureBoardError", ValueType.BOOLEAN,
                    new MetaItem<>(MetaItemType.LABEL, "Chyba měřicí desky"),
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.RULE_STATE),
                    new MetaItem<>(MetaItemType.STORE_DATA_POINTS),
                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                    .withOptional(true);

    public static final AttributeDescriptor<Boolean> FREEZING_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("freezing", ValueType.BOOLEAN,
                    new MetaItem<>(MetaItemType.LABEL, "Zamrznutí"),
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.RULE_STATE),
                    new MetaItem<>(MetaItemType.STORE_DATA_POINTS),
                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                    .withOptional(true);

    public static final AttributeDescriptor<Boolean> NO_WATER_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("noWater", ValueType.BOOLEAN,
                    new MetaItem<>(MetaItemType.LABEL, "Žádná voda"),
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.RULE_STATE),
                    new MetaItem<>(MetaItemType.STORE_DATA_POINTS),
                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                    .withOptional(true);

    public static final AttributeDescriptor<Integer> BATTERY_LEVEL_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("batteryLevel", ValueType.POSITIVE_INTEGER,
                    new MetaItem<>(MetaItemType.LABEL, "Stav baterie"),
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                    .withUnits(Constants.UNITS_PERCENTAGE)
                    .withOptional(true);

    public static final AssetDescriptor<WaterMeterAsset> WATER_METER_ASSET_DESCRIPTOR =
            new AssetDescriptor<>("water", "2e86ff", WaterMeterAsset.class);

    protected WaterMeterAsset() {
        // For JPA/Jackson
    }

    public WaterMeterAsset(String name) {
        super(name);
    }

    public Optional<String> getDevEui() {
        return getAttributes().getValue(DEV_EUI_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<ValueType.ObjectMap> getRawValue() {
        return getAttributes().getValue(RAW_VALUE_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<Double> getCurrentReading() {
        return getAttributes().getValue(CURRENT_READING_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<Boolean> getLeakage() {
        return getAttributes().getValue(LEAKAGE_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<Boolean> getBurst() {
        return getAttributes().getValue(BURST_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<Boolean> getOverflow() {
        return getAttributes().getValue(OVERFLOW_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<Boolean> getInverseFlow() {
        return getAttributes().getValue(INVERSE_FLOW_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<Boolean> getTamper() {
        return getAttributes().getValue(TAMPER_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<Boolean> getReverseInstallation() {
        return getAttributes().getValue(REVERSE_INSTALLATION_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<Boolean> getLowBattery() {
        return getAttributes().getValue(LOW_BATTERY_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<Boolean> getMeasureBoardError() {
        return getAttributes().getValue(MEASURE_BOARD_ERROR_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<Boolean> getFreezing() {
        return getAttributes().getValue(FREEZING_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<Boolean> getNoWater() {
        return getAttributes().getValue(NO_WATER_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<Integer> getBatteryLevel() {
        return getAttributes().getValue(BATTERY_LEVEL_ATTRIBUTE_DESCRIPTOR);
    }

}
