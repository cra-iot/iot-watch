package org.openremote.model.electricmeter;

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
 * Universal electric-meter asset. devEui + rawValue are required; every measurement
 * is an optional descriptor the operator adds per instance (a device reports only a
 * subset). Attribute-name strings equal the decoder's snake_case canonical keys, so
 * the electric-meter-decode rule needs no mapping table. The optional meter_id (= meter
 * serial) identifies the physical meter for correlation.
 */
@Entity
public class ElectricMeterAsset extends Asset<ElectricMeterAsset> {

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
                    new MetaItem<>(MetaItemType.LABEL, "Výrobní číslo elektroměru"),
                    new MetaItem<>(MetaItemType.READ_ONLY))
                    .withOptional(true);

    // Operator-provisioned routing/binding key: selects this asset's reading when a device
    // feeds several assets that claim the same field. Not READ_ONLY (the operator sets it),
    // not decoded (excluded in SameNameDecoder). Distinct from meter_id (the decoded serial).
    public static final AttributeDescriptor<String> EXTERNAL_ID_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("external_id", ValueType.TEXT,
                    new MetaItem<>(MetaItemType.LABEL, "Externí identifikátor"))
                    .withOptional(true);

    // ── Energy registers (kWh) ────────────────────────────────────────────────

    public static final AttributeDescriptor<Double> ENERGY_ACTIVE_TOTAL_ATTRIBUTE_DESCRIPTOR =
            energy("energy_active_total", "Činná energie celková");
    public static final AttributeDescriptor<Double> ENERGY_ACTIVE_T1_ATTRIBUTE_DESCRIPTOR =
            energy("energy_active_t1", "Činná energie T1");
    public static final AttributeDescriptor<Double> ENERGY_ACTIVE_T2_ATTRIBUTE_DESCRIPTOR =
            energy("energy_active_t2", "Činná energie T2");

    // ── Reactive energy (kvarh) ───────────────────────────────────────────────

    public static final AttributeDescriptor<Double> ENERGY_REACTIVE_ATTRIBUTE_DESCRIPTOR =
            reactive("energy_reactive", "Jalová energie");
    public static final AttributeDescriptor<Double> ENERGY_REACTIVE_1_ATTRIBUTE_DESCRIPTOR =
            reactive("energy_reactive_1", "Jalová energie – fáze 1");
    public static final AttributeDescriptor<Double> ENERGY_REACTIVE_2_ATTRIBUTE_DESCRIPTOR =
            reactive("energy_reactive_2", "Jalová energie – fáze 2");
    public static final AttributeDescriptor<Double> ENERGY_REACTIVE_3_ATTRIBUTE_DESCRIPTOR =
            reactive("energy_reactive_3", "Jalová energie – fáze 3");

    // ── Active power (kW) ─────────────────────────────────────────────────────

    public static final AttributeDescriptor<Double> POWER_TOTAL_ATTRIBUTE_DESCRIPTOR =
            power("power_total", "Celkový příkon");
    public static final AttributeDescriptor<Double> POWER_1_ATTRIBUTE_DESCRIPTOR =
            power("power_1", "Příkon fáze 1");
    public static final AttributeDescriptor<Double> POWER_2_ATTRIBUTE_DESCRIPTOR =
            power("power_2", "Příkon fáze 2");
    public static final AttributeDescriptor<Double> POWER_3_ATTRIBUTE_DESCRIPTOR =
            power("power_3", "Příkon fáze 3");

    // ── Apparent power (kVA) ──────────────────────────────────────────────────

    public static final AttributeDescriptor<Double> POWER_APPARENT_ATTRIBUTE_DESCRIPTOR =
            measurement("power_apparent", "Zdánlivý příkon", "kVA");

    // ── Power factor (dimensionless) ──────────────────────────────────────────

    public static final AttributeDescriptor<Double> POWER_FACTOR_ATTRIBUTE_DESCRIPTOR =
            measurement("power_factor", "Účiník");

    // ── Frequency (Hz) ────────────────────────────────────────────────────────

    public static final AttributeDescriptor<Double> FREQUENCY_ATTRIBUTE_DESCRIPTOR =
            measurement("frequency", "Frekvence", Constants.UNITS_HERTZ);

    // ── Voltage per phase (V) ─────────────────────────────────────────────────

    public static final AttributeDescriptor<Double> VOLTAGE_1_ATTRIBUTE_DESCRIPTOR =
            measurement("voltage_1", "Napětí fáze 1", Constants.UNITS_VOLT);
    public static final AttributeDescriptor<Double> VOLTAGE_2_ATTRIBUTE_DESCRIPTOR =
            measurement("voltage_2", "Napětí fáze 2", Constants.UNITS_VOLT);
    public static final AttributeDescriptor<Double> VOLTAGE_3_ATTRIBUTE_DESCRIPTOR =
            measurement("voltage_3", "Napětí fáze 3", Constants.UNITS_VOLT);

    // ── Current per phase (A) ─────────────────────────────────────────────────

    public static final AttributeDescriptor<Double> CURRENT_1_ATTRIBUTE_DESCRIPTOR =
            measurement("current_1", "Proud fáze 1", Constants.UNITS_AMP);
    public static final AttributeDescriptor<Double> CURRENT_2_ATTRIBUTE_DESCRIPTOR =
            measurement("current_2", "Proud fáze 2", Constants.UNITS_AMP);
    public static final AttributeDescriptor<Double> CURRENT_3_ATTRIBUTE_DESCRIPTOR =
            measurement("current_3", "Proud fáze 3", Constants.UNITS_AMP);

    // ── Temperatures (°C) ─────────────────────────────────────────────────────

    public static final AttributeDescriptor<Double> TEMPERATURE_1_ATTRIBUTE_DESCRIPTOR =
            measurement("temperature_1", "Teplota 1", Constants.UNITS_CELSIUS);
    public static final AttributeDescriptor<Double> TEMPERATURE_2_ATTRIBUTE_DESCRIPTOR =
            measurement("temperature_2", "Teplota 2", Constants.UNITS_CELSIUS);
    public static final AttributeDescriptor<Double> TEMPERATURE_3_ATTRIBUTE_DESCRIPTOR =
            measurement("temperature_3", "Teplota 3", Constants.UNITS_CELSIUS);
    public static final AttributeDescriptor<Double> TEMPERATURE_4_ATTRIBUTE_DESCRIPTOR =
            measurement("temperature_4", "Teplota 4", Constants.UNITS_CELSIUS);

    // ── Tariff (index) ────────────────────────────────────────────────────────

    public static final AttributeDescriptor<Integer> TARIFF_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("tariff", ValueType.POSITIVE_INTEGER,
                    new MetaItem<>(MetaItemType.LABEL, "Tarif"),
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.RULE_STATE),
                    new MetaItem<>(MetaItemType.STORE_DATA_POINTS),
                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                    .withOptional(true);

    public static final AssetDescriptor<ElectricMeterAsset> ELECTRIC_METER_ASSET_DESCRIPTOR =
            new AssetDescriptor<>("meter-electric", "FFB300", ElectricMeterAsset.class);

    // ── Descriptor factory helpers (measurement meta is identical everywhere) ──

    private static AttributeDescriptor<Double> measurement(String name, String label) {
        return measurement(name, label, (String[]) null);
    }

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

    private static AttributeDescriptor<Double> energy(String name, String label) {
        return measurement(name, label, Constants.UNITS_KILO, Constants.UNITS_WATT, Constants.UNITS_HOUR);
    }

    private static AttributeDescriptor<Double> reactive(String name, String label) {
        return measurement(name, label, "kvarh");
    }

    private static AttributeDescriptor<Double> power(String name, String label) {
        return measurement(name, label, Constants.UNITS_KILO, Constants.UNITS_WATT);
    }

    protected ElectricMeterAsset() {
        // For JPA/Jackson
    }

    public ElectricMeterAsset(String name) {
        super(name);
    }

    // ── Getters ────────────────────────────────────────────────────────────────

    public Optional<String> getDevEui() { return getAttributes().getValue(DEV_EUI_ATTRIBUTE_DESCRIPTOR); }
    public Optional<ValueType.ObjectMap> getRawValue() { return getAttributes().getValue(RAW_VALUE_ATTRIBUTE_DESCRIPTOR); }
    public Optional<String> getMeterId() { return getAttributes().getValue(METER_ID_ATTRIBUTE_DESCRIPTOR); }
    public Optional<String> getExternalId() { return getAttributes().getValue(EXTERNAL_ID_ATTRIBUTE_DESCRIPTOR); }

    public Optional<Double> getEnergyActiveTotal() { return getAttributes().getValue(ENERGY_ACTIVE_TOTAL_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getEnergyActiveT1() { return getAttributes().getValue(ENERGY_ACTIVE_T1_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getEnergyActiveT2() { return getAttributes().getValue(ENERGY_ACTIVE_T2_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getEnergyReactive() { return getAttributes().getValue(ENERGY_REACTIVE_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getEnergyReactive1() { return getAttributes().getValue(ENERGY_REACTIVE_1_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getEnergyReactive2() { return getAttributes().getValue(ENERGY_REACTIVE_2_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getEnergyReactive3() { return getAttributes().getValue(ENERGY_REACTIVE_3_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getPowerTotal() { return getAttributes().getValue(POWER_TOTAL_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getPower1() { return getAttributes().getValue(POWER_1_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getPower2() { return getAttributes().getValue(POWER_2_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getPower3() { return getAttributes().getValue(POWER_3_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getPowerApparent() { return getAttributes().getValue(POWER_APPARENT_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getPowerFactor() { return getAttributes().getValue(POWER_FACTOR_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getFrequency() { return getAttributes().getValue(FREQUENCY_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getVoltage1() { return getAttributes().getValue(VOLTAGE_1_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getVoltage2() { return getAttributes().getValue(VOLTAGE_2_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getVoltage3() { return getAttributes().getValue(VOLTAGE_3_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getCurrent1() { return getAttributes().getValue(CURRENT_1_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getCurrent2() { return getAttributes().getValue(CURRENT_2_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getCurrent3() { return getAttributes().getValue(CURRENT_3_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getTemperature1() { return getAttributes().getValue(TEMPERATURE_1_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getTemperature2() { return getAttributes().getValue(TEMPERATURE_2_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getTemperature3() { return getAttributes().getValue(TEMPERATURE_3_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getTemperature4() { return getAttributes().getValue(TEMPERATURE_4_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Integer> getTariff() { return getAttributes().getValue(TARIFF_ATTRIBUTE_DESCRIPTOR); }
}
