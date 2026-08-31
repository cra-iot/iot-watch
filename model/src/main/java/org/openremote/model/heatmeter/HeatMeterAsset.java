package org.openremote.model.heatmeter;

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
 * Universal heat-meter (calorimeter, EN 1434 / EN 13757-3 thermal energy meter) asset.
 * devEui + rawValue are required; every measurement and alarm is an optional descriptor the
 * operator adds per instance, because a device reports only a subset. Attribute-name strings
 * equal the decoder's canonical snake_case keys, so SameNameDecoder needs no mapping table.
 * The optional meter_id (= meter serial) identifies the physical meter for correlation.
 *
 * Canonical units, which the platform decoder scales to and OpenRemote never converts: all six
 * energy registers in GJ, volume in m3, power in kW, flow in m3/h, temperatures in degrees
 * Celsius and the temperature difference in kelvin. GJ rather than kWh because Czech heat
 * billing is quoted in GJ; a decoder normalises its device's Wh/J/cal/BTU encoding to GJ so the
 * attribute never changes meaning between devices.
 *
 * Alarm and diagnostic keys reuse WaterMeterAsset's spellings wherever the concept is identical
 * (low_battery, tamper, leakage, burst, inverse_flow, freezing, measure_board_error), so the
 * platform decoder side stays uniform across meter types. Alarm booleans are cracked out of the
 * device's status bytes by the platform decoder, not here; this model holds no bit logic.
 *
 * See docs/heat-meter-dictionary.md for the key contract and docs/decoder-dictionary.md for the
 * device-agnostic envelope, reserved keys and failure signalling.
 */
@Entity
public class HeatMeterAsset extends Asset<HeatMeterAsset> {

    // -- Identity / ingest ----------------------------------------------------

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
                    new MetaItem<>(MetaItemType.LABEL, "Výrobní číslo měřiče tepla"),
                    new MetaItem<>(MetaItemType.READ_ONLY))
                    .withOptional(true);

    // Operator-provisioned routing/binding key: selects this asset's reading when a device
    // feeds several assets that claim the same field. Not READ_ONLY (the operator sets it),
    // not decoded (excluded in SameNameDecoder). Distinct from meter_id (the decoded serial).
    public static final AttributeDescriptor<String> EXTERNAL_ID_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("external_id", ValueType.TEXT,
                    new MetaItem<>(MetaItemType.LABEL, "Externí identifikátor"))
                    .withOptional(true);

    // -- Energy registers (GJ) ------------------------------------------------

    // Cumulative heat energy: the primary index of a heat meter.
    public static final AttributeDescriptor<Double> ENERGY_HEATING_ATTRIBUTE_DESCRIPTOR =
            energy("energy_heating", "Tepelná energie");
    // Cumulative cooling energy on a bidirectional (heat/cool) meter.
    public static final AttributeDescriptor<Double> ENERGY_COOLING_ATTRIBUTE_DESCRIPTOR =
            energy("energy_cooling", "Chladicí energie");
    // Billing snapshots (EN 13757-3 storage-number registers: due date / set day).
    public static final AttributeDescriptor<Double> ENERGY_HEATING_PREVIOUS_MONTH_ATTRIBUTE_DESCRIPTOR =
            energy("energy_heating_previous_month", "Tepelná energie k předchozímu měsíci");
    public static final AttributeDescriptor<Double> ENERGY_HEATING_PREVIOUS_DAY_ATTRIBUTE_DESCRIPTOR =
            energy("energy_heating_previous_day", "Tepelná energie k předchozímu dni");
    public static final AttributeDescriptor<Double> ENERGY_TARIFF_1_ATTRIBUTE_DESCRIPTOR =
            energy("energy_tariff_1", "Tepelná energie T1");
    public static final AttributeDescriptor<Double> ENERGY_TARIFF_2_ATTRIBUTE_DESCRIPTOR =
            energy("energy_tariff_2", "Tepelná energie T2");

    // -- Volume registers (m3) ------------------------------------------------

    // Cumulative volume of the heat-carrier medium through the meter. Deliberately not named
    // currentReading: that camelCase name exists only to preserve WaterMeterAsset's data-point
    // history, and a heat meter's primary index is energy, not volume.
    public static final AttributeDescriptor<Double> VOLUME_ATTRIBUTE_DESCRIPTOR =
            volume("volume", "Objem teplonosné látky");
    public static final AttributeDescriptor<Double> VOLUME_PREVIOUS_MONTH_ATTRIBUTE_DESCRIPTOR =
            volume("volume_previous_month", "Objem k předchozímu měsíci");

    // -- Thermal power (kW) ---------------------------------------------------

    public static final AttributeDescriptor<Double> POWER_ATTRIBUTE_DESCRIPTOR =
            power("power", "Okamžitý tepelný výkon");
    // EN 13757-3 function field 01b (maximum), not a separate quantity.
    public static final AttributeDescriptor<Double> POWER_MAX_ATTRIBUTE_DESCRIPTOR =
            power("power_max", "Maximální tepelný výkon");

    // -- Flow (m3/h) ----------------------------------------------------------

    public static final AttributeDescriptor<Double> FLOW_RATE_ATTRIBUTE_DESCRIPTOR =
            flow("flow_rate", "Okamžitý průtok");
    public static final AttributeDescriptor<Double> FLOW_RATE_MAX_ATTRIBUTE_DESCRIPTOR =
            flow("flow_rate_max", "Maximální průtok");

    // -- Temperatures ---------------------------------------------------------

    public static final AttributeDescriptor<Double> TEMPERATURE_FLOW_ATTRIBUTE_DESCRIPTOR =
            measurement("temperature_flow", "Teplota přívodu", Constants.UNITS_CELSIUS);
    public static final AttributeDescriptor<Double> TEMPERATURE_RETURN_ATTRIBUTE_DESCRIPTOR =
            measurement("temperature_return", "Teplota zpátečky", Constants.UNITS_CELSIUS);
    // Kelvin, not Celsius: this is a difference, not a temperature.
    public static final AttributeDescriptor<Double> TEMPERATURE_DIFFERENCE_ATTRIBUTE_DESCRIPTOR =
            measurement("temperature_difference", "Teplotní rozdíl", Constants.UNITS_KELVIN);

    // -- Diagnostics ----------------------------------------------------------

    public static final AttributeDescriptor<Double> OPERATING_HOURS_ATTRIBUTE_DESCRIPTOR =
            measurement("operating_hours", "Provozní hodiny", Constants.UNITS_HOUR);
    public static final AttributeDescriptor<Double> ERROR_HOURS_ATTRIBUTE_DESCRIPTOR =
            measurement("error_hours", "Hodiny v chybovém stavu", Constants.UNITS_HOUR);

    // Optional; no STORE_DATA_POINTS / RULE_STATE (the low_battery boolean carries the alarm).
    public static final AttributeDescriptor<Integer> BATTERY_LEVEL_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("battery_level", ValueType.POSITIVE_INTEGER,
                    new MetaItem<>(MetaItemType.LABEL, "Stav baterie"),
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                    .withUnits(Constants.UNITS_PERCENTAGE)
                    .withOptional(true);

    // The device's raw status/error bitfield as the decoder read it (e.g. "0x04"), kept because
    // the bit meanings are device-specific and often undocumented: rawValue holds only the
    // latest message, so without data points here the bit history is unrecoverable. Text, not a
    // number - it is a bitfield, and arithmetic on it is meaningless.
    public static final AttributeDescriptor<String> ERROR_FLAGS_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("error_flags", ValueType.TEXT,
                    new MetaItem<>(MetaItemType.LABEL, "Chybové příznaky zařízení"),
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.STORE_DATA_POINTS),
                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                    .withOptional(true);

    // -- Alarms (boolean) -----------------------------------------------------

    // Any bit set in the device's status bitfield; the coarse companion to error_flags, for
    // devices whose individual bit meanings are not documented.
    public static final AttributeDescriptor<Boolean> METER_ERROR_ATTRIBUTE_DESCRIPTOR =
            alarm("meter_error", "Chyba měřiče");
    // The radio module could not read the meter head. Distinct from measure_board_error (a
    // fault in the meter's own electronics) and from meter_error (a fault the meter reported).
    public static final AttributeDescriptor<Boolean> METER_READ_ERROR_ATTRIBUTE_DESCRIPTOR =
            alarm("meter_read_error", "Chyba čtení měřiče");
    public static final AttributeDescriptor<Boolean> LOW_BATTERY_ATTRIBUTE_DESCRIPTOR =
            alarm("low_battery", "Slabá baterie");
    public static final AttributeDescriptor<Boolean> TAMPER_ATTRIBUTE_DESCRIPTOR =
            alarm("tamper", "Neoprávněná manipulace");
    public static final AttributeDescriptor<Boolean> LEAKAGE_ATTRIBUTE_DESCRIPTOR =
            alarm("leakage", "Únik");
    public static final AttributeDescriptor<Boolean> BURST_ATTRIBUTE_DESCRIPTOR =
            alarm("burst", "Prasklé potrubí");
    public static final AttributeDescriptor<Boolean> INVERSE_FLOW_ATTRIBUTE_DESCRIPTOR =
            alarm("inverse_flow", "Zpětný tok");
    public static final AttributeDescriptor<Boolean> FREEZING_ATTRIBUTE_DESCRIPTOR =
            alarm("freezing", "Zamrznutí");
    public static final AttributeDescriptor<Boolean> MEASURE_BOARD_ERROR_ATTRIBUTE_DESCRIPTOR =
            alarm("measure_board_error", "Chyba měřicí desky");
    public static final AttributeDescriptor<Boolean> NO_FLOW_ATTRIBUTE_DESCRIPTOR =
            alarm("no_flow", "Žádný průtok");
    public static final AttributeDescriptor<Boolean> AIR_IN_PIPE_ATTRIBUTE_DESCRIPTOR =
            alarm("air_in_pipe", "Vzduch v potrubí");
    public static final AttributeDescriptor<Boolean> TEMPERATURE_SENSOR_ERROR_ATTRIBUTE_DESCRIPTOR =
            alarm("temperature_sensor_error", "Chyba teplotního senzoru");
    public static final AttributeDescriptor<Boolean> FLOW_SENSOR_ERROR_ATTRIBUTE_DESCRIPTOR =
            alarm("flow_sensor_error", "Chyba průtokového senzoru");

    public static final AssetDescriptor<HeatMeterAsset> HEAT_METER_ASSET_DESCRIPTOR =
            new AssetDescriptor<>("radiator", "E64A19", HeatMeterAsset.class);

    // -- Descriptor factory helpers (measurement/alarm meta is identical everywhere) --

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
        return measurement(name, label, Constants.UNITS_GIGA, Constants.UNITS_JOULE);
    }

    private static AttributeDescriptor<Double> volume(String name, String label) {
        return measurement(name, label, Constants.UNITS_METRE, Constants.UNITS_CUBED);
    }

    private static AttributeDescriptor<Double> power(String name, String label) {
        return measurement(name, label, Constants.UNITS_KILO, Constants.UNITS_WATT);
    }

    private static AttributeDescriptor<Double> flow(String name, String label) {
        return measurement(name, label,
                Constants.UNITS_METRE, Constants.UNITS_CUBED, Constants.UNITS_PER, Constants.UNITS_HOUR);
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

    protected HeatMeterAsset() {
        // For JPA/Jackson
    }

    public HeatMeterAsset(String name) {
        super(name);
    }

    // -- Getters --------------------------------------------------------------

    public Optional<String> getDevEui() { return getAttributes().getValue(DEV_EUI_ATTRIBUTE_DESCRIPTOR); }
    public Optional<ValueType.ObjectMap> getRawValue() { return getAttributes().getValue(RAW_VALUE_ATTRIBUTE_DESCRIPTOR); }
    public Optional<String> getMeterId() { return getAttributes().getValue(METER_ID_ATTRIBUTE_DESCRIPTOR); }
    public Optional<String> getExternalId() { return getAttributes().getValue(EXTERNAL_ID_ATTRIBUTE_DESCRIPTOR); }

    public Optional<Double> getEnergyHeating() { return getAttributes().getValue(ENERGY_HEATING_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getEnergyCooling() { return getAttributes().getValue(ENERGY_COOLING_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getEnergyHeatingPreviousMonth() { return getAttributes().getValue(ENERGY_HEATING_PREVIOUS_MONTH_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getEnergyHeatingPreviousDay() { return getAttributes().getValue(ENERGY_HEATING_PREVIOUS_DAY_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getEnergyTariff1() { return getAttributes().getValue(ENERGY_TARIFF_1_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getEnergyTariff2() { return getAttributes().getValue(ENERGY_TARIFF_2_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getVolume() { return getAttributes().getValue(VOLUME_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getVolumePreviousMonth() { return getAttributes().getValue(VOLUME_PREVIOUS_MONTH_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getPower() { return getAttributes().getValue(POWER_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getPowerMax() { return getAttributes().getValue(POWER_MAX_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getFlowRate() { return getAttributes().getValue(FLOW_RATE_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getFlowRateMax() { return getAttributes().getValue(FLOW_RATE_MAX_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getTemperatureFlow() { return getAttributes().getValue(TEMPERATURE_FLOW_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getTemperatureReturn() { return getAttributes().getValue(TEMPERATURE_RETURN_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getTemperatureDifference() { return getAttributes().getValue(TEMPERATURE_DIFFERENCE_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getOperatingHours() { return getAttributes().getValue(OPERATING_HOURS_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getErrorHours() { return getAttributes().getValue(ERROR_HOURS_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Integer> getBatteryLevel() { return getAttributes().getValue(BATTERY_LEVEL_ATTRIBUTE_DESCRIPTOR); }
    public Optional<String> getErrorFlags() { return getAttributes().getValue(ERROR_FLAGS_ATTRIBUTE_DESCRIPTOR); }

    public Optional<Boolean> getMeterError() { return getAttributes().getValue(METER_ERROR_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getMeterReadError() { return getAttributes().getValue(METER_READ_ERROR_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getLowBattery() { return getAttributes().getValue(LOW_BATTERY_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getTamper() { return getAttributes().getValue(TAMPER_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getLeakage() { return getAttributes().getValue(LEAKAGE_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getBurst() { return getAttributes().getValue(BURST_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getInverseFlow() { return getAttributes().getValue(INVERSE_FLOW_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getFreezing() { return getAttributes().getValue(FREEZING_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getMeasureBoardError() { return getAttributes().getValue(MEASURE_BOARD_ERROR_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getNoFlow() { return getAttributes().getValue(NO_FLOW_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getAirInPipe() { return getAttributes().getValue(AIR_IN_PIPE_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getTemperatureSensorError() { return getAttributes().getValue(TEMPERATURE_SENSOR_ERROR_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getFlowSensorError() { return getAttributes().getValue(FLOW_SENSOR_ERROR_ATTRIBUTE_DESCRIPTOR); }
}
