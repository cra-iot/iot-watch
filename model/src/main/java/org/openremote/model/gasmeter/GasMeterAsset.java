package org.openremote.model.gasmeter;

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
 * Universal gas-meter (EN 1359 diaphragm / EN 12480 rotary meter, with or without an EN 12405
 * volume conversion device) asset. devEui + rawValue are required; every measurement and alarm
 * is an optional descriptor the operator adds per instance, because a device reports only a
 * subset - a retrofit pulse module carries volume and little else, a converter carries the
 * whole chain. Attribute-name strings equal the decoder's canonical snake_case keys, so
 * SameNameDecoder needs no mapping table. The optional meter_id (= meter serial) identifies
 * the physical meter for correlation.
 *
 * Canonical units, which the platform decoder scales to and OpenRemote never converts: both
 * volume families in m3, energy in kWh, flow in m3/h, pressure in kPa and gas temperature in
 * degrees Celsius. kWh rather than the heat meter's GJ because Czech gas is billed in kWh, and
 * it matches ElectricMeterAsset.
 *
 * The two volume families are distinct quantities, not a unit choice: volume* is measured at
 * metering (working) conditions - what the meter's own counter shows - while volume_standard*
 * is converted to base conditions (15 degrees Celsius, 101.325 kPa). Only a meter with a
 * conversion device reports the latter, and a decoder must never derive one from the other.
 *
 * Alarm and diagnostic keys reuse WaterMeterAsset's and HeatMeterAsset's spellings wherever
 * the concept is identical (low_battery, tamper, leakage, inverse_flow, no_flow,
 * measure_board_error, meter_error, meter_read_error, temperature_sensor_error), so the
 * platform decoder side stays uniform across meter types. Alarm booleans are cracked out of
 * the device's status bytes by the platform decoder, not here; this model holds no bit logic.
 *
 * valve_closed is the reported state of an integrated shut-off valve, read-only like every
 * other decoded attribute: ingest is one-way, so this model carries no valve control.
 *
 * See docs/gas-meter-dictionary.md for the key contract and docs/ingest-data-spec.md for the
 * device-agnostic envelope, reserved keys and failure signalling.
 */
@Entity
public class GasMeterAsset extends Asset<GasMeterAsset> {

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
                    new MetaItem<>(MetaItemType.LABEL, "Výrobní číslo plynoměru"),
                    new MetaItem<>(MetaItemType.READ_ONLY))
                    .withOptional(true);

    // Operator-provisioned routing/binding key: selects this asset's reading when a device
    // feeds several assets that claim the same field. Not READ_ONLY (the operator sets it),
    // not decoded (excluded in SameNameDecoder). Distinct from meter_id (the decoded serial).
    public static final AttributeDescriptor<String> EXTERNAL_ID_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("external_id", ValueType.TEXT,
                    new MetaItem<>(MetaItemType.LABEL, "Externí identifikátor"))
                    .withOptional(true);

    // -- Volume at metering conditions (m3) -----------------------------------

    // The meter's own cumulative counter: the primary index of a gas meter. Deliberately not
    // named currentReading - that camelCase name exists only to preserve WaterMeterAsset's
    // data-point history, and nothing here predates the snake_case convention.
    public static final AttributeDescriptor<Double> VOLUME_ATTRIBUTE_DESCRIPTOR =
            volume("volume", "Objem plynu");
    // Billing snapshots (EN 13757-3 storage-number registers: due date / set day).
    public static final AttributeDescriptor<Double> VOLUME_PREVIOUS_MONTH_ATTRIBUTE_DESCRIPTOR =
            volume("volume_previous_month", "Objem k předchozímu měsíci");
    public static final AttributeDescriptor<Double> VOLUME_PREVIOUS_DAY_ATTRIBUTE_DESCRIPTOR =
            volume("volume_previous_day", "Objem k předchozímu dni");

    // -- Volume at base conditions (m3, 15 degrees Celsius / 101.325 kPa) ------

    // Přepočtený objem: reported only by a meter with a volume conversion device. Same unit as
    // volume, different quantity - never derive one from the other.
    public static final AttributeDescriptor<Double> VOLUME_STANDARD_ATTRIBUTE_DESCRIPTOR =
            volume("volume_standard", "Přepočtený objem");
    public static final AttributeDescriptor<Double> VOLUME_STANDARD_PREVIOUS_MONTH_ATTRIBUTE_DESCRIPTOR =
            volume("volume_standard_previous_month", "Přepočtený objem k předchozímu měsíci");

    // -- Energy (kWh) ---------------------------------------------------------

    // Billed energy, where the device reports it: base-condition volume times calorific value.
    // A meter that does not know its calorific value reports nothing here, and OpenRemote never
    // computes it - the calorific value is a supplier tariff input, not a measurement.
    public static final AttributeDescriptor<Double> ENERGY_ATTRIBUTE_DESCRIPTOR =
            energy("energy", "Energie");
    public static final AttributeDescriptor<Double> ENERGY_PREVIOUS_MONTH_ATTRIBUTE_DESCRIPTOR =
            energy("energy_previous_month", "Energie k předchozímu měsíci");

    // -- Flow (m3/h) ----------------------------------------------------------

    public static final AttributeDescriptor<Double> FLOW_RATE_ATTRIBUTE_DESCRIPTOR =
            flow("flow_rate", "Okamžitý průtok");
    // EN 13757-3 function field 01b (maximum), not a separate quantity.
    public static final AttributeDescriptor<Double> FLOW_RATE_MAX_ATTRIBUTE_DESCRIPTOR =
            flow("flow_rate_max", "Maximální průtok");

    // -- Conversion inputs ----------------------------------------------------

    // Absolute, not gauge: absolute pressure is the quantity an EN 12405 converter uses, and a
    // decoder reading a gauge sensor adds the barometric reference before emitting.
    public static final AttributeDescriptor<Double> PRESSURE_ATTRIBUTE_DESCRIPTOR =
            measurement("pressure", "Absolutní tlak plynu", Constants.UNITS_KILO, Constants.UNITS_PASCAL);
    // Named gas_temperature, not temperature: it is the temperature of the medium at the meter,
    // the conversion input - never the module's own board temperature.
    public static final AttributeDescriptor<Double> GAS_TEMPERATURE_ATTRIBUTE_DESCRIPTOR =
            measurement("gas_temperature", "Teplota plynu", Constants.UNITS_CELSIUS);

    // -- Diagnostics ----------------------------------------------------------

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
    // Continuous flow the meter judges to be a leak downstream of it. Not a gas detector: a
    // methane sensor is a separate device, and so a separate asset type.
    public static final AttributeDescriptor<Boolean> LEAKAGE_ATTRIBUTE_DESCRIPTOR =
            alarm("leakage", "Únik plynu");
    public static final AttributeDescriptor<Boolean> INVERSE_FLOW_ATTRIBUTE_DESCRIPTOR =
            alarm("inverse_flow", "Zpětný tok");
    public static final AttributeDescriptor<Boolean> NO_FLOW_ATTRIBUTE_DESCRIPTOR =
            alarm("no_flow", "Žádný průtok");
    public static final AttributeDescriptor<Boolean> MEASURE_BOARD_ERROR_ATTRIBUTE_DESCRIPTOR =
            alarm("measure_board_error", "Chyba měřicí desky");
    public static final AttributeDescriptor<Boolean> TEMPERATURE_SENSOR_ERROR_ATTRIBUTE_DESCRIPTOR =
            alarm("temperature_sensor_error", "Chyba teplotního senzoru");
    public static final AttributeDescriptor<Boolean> PRESSURE_SENSOR_ERROR_ATTRIBUTE_DESCRIPTOR =
            alarm("pressure_sensor_error", "Chyba tlakového senzoru");
    // State of an integrated shut-off valve, as the device reported it. Read-only: there is no
    // downlink path to the device, so this is never a control.
    public static final AttributeDescriptor<Boolean> VALVE_CLOSED_ATTRIBUTE_DESCRIPTOR =
            alarm("valve_closed", "Uzavřený ventil");

    // "gas-cylinder", not "meter-gas": the Manager UI resolves this name against @mdi/js, and
    // OpenRemote 1.29.0 pins ^5.9.55, where mdiMeterGas does not exist yet (added in MDI 6.5).
    // Teal keeps the type distinct from water blue, electric amber and heat orange.
    public static final AssetDescriptor<GasMeterAsset> GAS_METER_ASSET_DESCRIPTOR =
            new AssetDescriptor<>("gas-cylinder", "00897B", GasMeterAsset.class);

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

    private static AttributeDescriptor<Double> volume(String name, String label) {
        return measurement(name, label, Constants.UNITS_METRE, Constants.UNITS_CUBED);
    }

    private static AttributeDescriptor<Double> energy(String name, String label) {
        return measurement(name, label, Constants.UNITS_KILO, Constants.UNITS_WATT, Constants.UNITS_HOUR);
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

    protected GasMeterAsset() {
        // For JPA/Jackson
    }

    public GasMeterAsset(String name) {
        super(name);
    }

    // -- Getters --------------------------------------------------------------

    public Optional<String> getDevEui() { return getAttributes().getValue(DEV_EUI_ATTRIBUTE_DESCRIPTOR); }
    public Optional<ValueType.ObjectMap> getRawValue() { return getAttributes().getValue(RAW_VALUE_ATTRIBUTE_DESCRIPTOR); }
    public Optional<String> getMeterId() { return getAttributes().getValue(METER_ID_ATTRIBUTE_DESCRIPTOR); }
    public Optional<String> getExternalId() { return getAttributes().getValue(EXTERNAL_ID_ATTRIBUTE_DESCRIPTOR); }

    public Optional<Double> getVolume() { return getAttributes().getValue(VOLUME_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getVolumePreviousMonth() { return getAttributes().getValue(VOLUME_PREVIOUS_MONTH_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getVolumePreviousDay() { return getAttributes().getValue(VOLUME_PREVIOUS_DAY_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getVolumeStandard() { return getAttributes().getValue(VOLUME_STANDARD_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getVolumeStandardPreviousMonth() { return getAttributes().getValue(VOLUME_STANDARD_PREVIOUS_MONTH_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getEnergy() { return getAttributes().getValue(ENERGY_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getEnergyPreviousMonth() { return getAttributes().getValue(ENERGY_PREVIOUS_MONTH_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getFlowRate() { return getAttributes().getValue(FLOW_RATE_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getFlowRateMax() { return getAttributes().getValue(FLOW_RATE_MAX_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getPressure() { return getAttributes().getValue(PRESSURE_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getGasTemperature() { return getAttributes().getValue(GAS_TEMPERATURE_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Integer> getBatteryLevel() { return getAttributes().getValue(BATTERY_LEVEL_ATTRIBUTE_DESCRIPTOR); }
    public Optional<String> getErrorFlags() { return getAttributes().getValue(ERROR_FLAGS_ATTRIBUTE_DESCRIPTOR); }

    public Optional<Boolean> getMeterError() { return getAttributes().getValue(METER_ERROR_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getMeterReadError() { return getAttributes().getValue(METER_READ_ERROR_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getLowBattery() { return getAttributes().getValue(LOW_BATTERY_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getTamper() { return getAttributes().getValue(TAMPER_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getLeakage() { return getAttributes().getValue(LEAKAGE_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getInverseFlow() { return getAttributes().getValue(INVERSE_FLOW_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getNoFlow() { return getAttributes().getValue(NO_FLOW_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getMeasureBoardError() { return getAttributes().getValue(MEASURE_BOARD_ERROR_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getTemperatureSensorError() { return getAttributes().getValue(TEMPERATURE_SENSOR_ERROR_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getPressureSensorError() { return getAttributes().getValue(PRESSURE_SENSOR_ERROR_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getValveClosed() { return getAttributes().getValue(VALVE_CLOSED_ATTRIBUTE_DESCRIPTOR); }
}
