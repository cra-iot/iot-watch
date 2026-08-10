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

/**
 * Universal water-meter asset. devEui + rawValue are required and currentReading is the
 * required cumulative forward-volume index; every other measurement/alarm is an optional
 * descriptor the operator adds per instance (a device reports only a subset).
 * Attribute-name strings equal the decoder's canonical keys, so the water-meter-decode
 * rule needs no mapping table. currentReading, devEui and rawValue keep their historic
 * camelCase names (ingest contract / preserved data-point history); all other
 * measurement and alarm keys are snake_case. Alarm booleans are decoded by the platform
 * decoder, not here. May also serve as a child of a water concentrator: set meter_id
 * (= meter serial) and leave devEui empty (gateway fan-out is not yet modelled).
 */
@Entity
public class WaterMeterAsset extends Asset<WaterMeterAsset> {

    // ── Identity / ingest ─────────────────────────────────────────────────────

    public static final AttributeDescriptor<String> DEV_EUI_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("devEui", ValueType.TEXT,
                    new MetaItem<>(MetaItemType.LABEL, "Identifikátor zařízení (devEUI)"));

    public static final AttributeDescriptor<ValueType.ObjectMap> RAW_VALUE_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("rawValue", ValueType.JSON_OBJECT,
                    new MetaItem<>(MetaItemType.LABEL, "Surová data"),
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.RULE_STATE));

    public static final AttributeDescriptor<String> METER_ID_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("meter_id", ValueType.TEXT,
                    new MetaItem<>(MetaItemType.LABEL, "Výrobní číslo vodoměru"),
                    new MetaItem<>(MetaItemType.READ_ONLY))
                    .withOptional(true);

    // ── Volume registers (m³) ──────────────────────────────────────────────────

    // Cumulative forward-volume index. REQUIRED and kept camelCase to preserve its
    // data-point history (a rename would need a SQL migration). No RULE_STATE.
    public static final AttributeDescriptor<Double> CURRENT_READING_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("currentReading", ValueType.NUMBER,
                    new MetaItem<>(MetaItemType.LABEL, "Aktuální stav vodoměru"),
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.STORE_DATA_POINTS),
                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                    .withUnits(Constants.UNITS_METRE, Constants.UNITS_CUBED);

    public static final AttributeDescriptor<Double> VOLUME_REVERSE_ATTRIBUTE_DESCRIPTOR =
            volume("volume_reverse", "Zpětný objem");
    public static final AttributeDescriptor<Double> VOLUME_PREVIOUS_DAY_ATTRIBUTE_DESCRIPTOR =
            volume("volume_previous_day", "Stav k předchozímu dni");

    // ── Flow (m³/h) ────────────────────────────────────────────────────────────

    public static final AttributeDescriptor<Double> FLOW_RATE_ATTRIBUTE_DESCRIPTOR =
            measurement("flow_rate", "Okamžitý průtok",
                    Constants.UNITS_METRE, Constants.UNITS_CUBED, Constants.UNITS_PER, Constants.UNITS_HOUR);

    // ── Temperatures (°C) ────────────────────────────────────────────────────────

    public static final AttributeDescriptor<Double> WATER_TEMPERATURE_ATTRIBUTE_DESCRIPTOR =
            measurement("water_temperature", "Teplota vody", Constants.UNITS_CELSIUS);
    public static final AttributeDescriptor<Double> TEMPERATURE_MIN_ATTRIBUTE_DESCRIPTOR =
            measurement("temperature_min", "Minimální denní teplota", Constants.UNITS_CELSIUS);
    public static final AttributeDescriptor<Double> TEMPERATURE_MAX_ATTRIBUTE_DESCRIPTOR =
            measurement("temperature_max", "Maximální denní teplota", Constants.UNITS_CELSIUS);

    // ── Battery (%) ────────────────────────────────────────────────────────────

    // Optional; no STORE_DATA_POINTS / RULE_STATE (the low_battery boolean carries the alarm).
    public static final AttributeDescriptor<Integer> BATTERY_LEVEL_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("battery_level", ValueType.POSITIVE_INTEGER,
                    new MetaItem<>(MetaItemType.LABEL, "Stav baterie"),
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                    .withUnits(Constants.UNITS_PERCENTAGE)
                    .withOptional(true);

    // ── Alarms (boolean) ─────────────────────────────────────────────────────────

    public static final AttributeDescriptor<Boolean> LEAKAGE_ATTRIBUTE_DESCRIPTOR =
            alarm("leakage", "Únik vody");
    public static final AttributeDescriptor<Boolean> BURST_ATTRIBUTE_DESCRIPTOR =
            alarm("burst", "Prasklé potrubí");
    public static final AttributeDescriptor<Boolean> OVERFLOW_ATTRIBUTE_DESCRIPTOR =
            alarm("overflow", "Přetečení");
    public static final AttributeDescriptor<Boolean> INVERSE_FLOW_ATTRIBUTE_DESCRIPTOR =
            alarm("inverse_flow", "Zpětný tok");
    public static final AttributeDescriptor<Boolean> TAMPER_ATTRIBUTE_DESCRIPTOR =
            alarm("tamper", "Neoprávněná manipulace");
    public static final AttributeDescriptor<Boolean> REVERSE_INSTALLATION_ATTRIBUTE_DESCRIPTOR =
            alarm("reverse_installation", "Opačná instalace");
    public static final AttributeDescriptor<Boolean> LOW_BATTERY_ATTRIBUTE_DESCRIPTOR =
            alarm("low_battery", "Slabá baterie");
    public static final AttributeDescriptor<Boolean> MEASURE_BOARD_ERROR_ATTRIBUTE_DESCRIPTOR =
            alarm("measure_board_error", "Chyba měřicí desky");
    public static final AttributeDescriptor<Boolean> FREEZING_ATTRIBUTE_DESCRIPTOR =
            alarm("freezing", "Zamrznutí");
    public static final AttributeDescriptor<Boolean> NO_WATER_ATTRIBUTE_DESCRIPTOR =
            alarm("no_water", "Žádná voda");

    public static final AssetDescriptor<WaterMeterAsset> WATER_METER_ASSET_DESCRIPTOR =
            new AssetDescriptor<>("water", "2e86ff", WaterMeterAsset.class);

    // ── Descriptor factory helpers (measurement/alarm meta is identical everywhere) ──

    private static AttributeDescriptor<Double> measurement(String name, String label, String... units) {
        AttributeDescriptor<Double> d = new AttributeDescriptor<>(name, ValueType.NUMBER,
                new MetaItem<>(MetaItemType.LABEL, label),
                new MetaItem<>(MetaItemType.READ_ONLY),
                new MetaItem<>(MetaItemType.RULE_STATE),
                new MetaItem<>(MetaItemType.STORE_DATA_POINTS),
                new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                .withOptional(true);
        return (units != null && units.length > 0 && units[0] != null) ? d.withUnits(units) : d;
    }

    private static AttributeDescriptor<Double> volume(String name, String label) {
        return measurement(name, label, Constants.UNITS_METRE, Constants.UNITS_CUBED);
    }

    private static AttributeDescriptor<Boolean> alarm(String name, String label) {
        return new AttributeDescriptor<Boolean>(name, ValueType.BOOLEAN,
                new MetaItem<>(MetaItemType.LABEL, label),
                new MetaItem<>(MetaItemType.READ_ONLY),
                new MetaItem<>(MetaItemType.RULE_STATE),
                new MetaItem<>(MetaItemType.STORE_DATA_POINTS),
                new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                .withOptional(true);
    }

    protected WaterMeterAsset() {
        // For JPA/Jackson
    }

    public WaterMeterAsset(String name) {
        super(name);
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public Optional<String> getDevEui() { return getAttributes().getValue(DEV_EUI_ATTRIBUTE_DESCRIPTOR); }
    public Optional<ValueType.ObjectMap> getRawValue() { return getAttributes().getValue(RAW_VALUE_ATTRIBUTE_DESCRIPTOR); }
    public Optional<String> getMeterId() { return getAttributes().getValue(METER_ID_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getCurrentReading() { return getAttributes().getValue(CURRENT_READING_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getVolumeReverse() { return getAttributes().getValue(VOLUME_REVERSE_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getVolumePreviousDay() { return getAttributes().getValue(VOLUME_PREVIOUS_DAY_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getFlowRate() { return getAttributes().getValue(FLOW_RATE_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getWaterTemperature() { return getAttributes().getValue(WATER_TEMPERATURE_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getTemperatureMin() { return getAttributes().getValue(TEMPERATURE_MIN_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getTemperatureMax() { return getAttributes().getValue(TEMPERATURE_MAX_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Integer> getBatteryLevel() { return getAttributes().getValue(BATTERY_LEVEL_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getLeakage() { return getAttributes().getValue(LEAKAGE_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getBurst() { return getAttributes().getValue(BURST_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getOverflow() { return getAttributes().getValue(OVERFLOW_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getInverseFlow() { return getAttributes().getValue(INVERSE_FLOW_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getTamper() { return getAttributes().getValue(TAMPER_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getReverseInstallation() { return getAttributes().getValue(REVERSE_INSTALLATION_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getLowBattery() { return getAttributes().getValue(LOW_BATTERY_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getMeasureBoardError() { return getAttributes().getValue(MEASURE_BOARD_ERROR_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getFreezing() { return getAttributes().getValue(FREEZING_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getNoWater() { return getAttributes().getValue(NO_WATER_ATTRIBUTE_DESCRIPTOR); }
}
