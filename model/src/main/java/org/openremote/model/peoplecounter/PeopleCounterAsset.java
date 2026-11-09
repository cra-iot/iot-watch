package org.openremote.model.peoplecounter;

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
 * People counter asset: one device counting passages in and out of one entrance (Milesight
 * VS350 passage counter, VS351 thermopile counter - both report the same values). A shop with
 * several entrances is modelled as one counter per entrance under a shop parent asset.
 *
 * devEui + rawValue are required; every measurement and alarm is an optional descriptor the
 * operator adds per instance - a mains-powered VS351 reports no battery. Attribute-name strings
 * equal the decoder's canonical snake_case keys, so SameNameDecoder needs no mapping table.
 * The optional meter_id carries the device serial: the name is shared with the meter types so
 * the Fleet page's identity column and search cover counters without a type-specific case.
 *
 * period_in / period_out count the passages in the device's last reporting interval and are
 * the values to aggregate over time. total_in / total_out are the device's own 16-bit
 * counters: they wrap at 65535 and restart after a device reset or a counter clear, so they
 * are kept as received and are not lifetime totals.
 *
 * The count-threshold alarms have no release event on the device; the platform decoder clears
 * them with the next plain count report. Alarm booleans are cracked out of the frames by the
 * platform decoder, not here; this model holds no bit logic.
 *
 * See docs/people-counter-dictionary.md for the key contract and docs/ingest-data-spec.md for
 * the device-agnostic envelope, reserved keys and failure signalling.
 */
@Entity
public class PeopleCounterAsset extends Asset<PeopleCounterAsset> {

    // -- Identity / ingest ----------------------------------------------------

    public static final AttributeDescriptor<String> DEV_EUI_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("devEui", ValueType.TEXT,
                    new MetaItem<>(MetaItemType.LABEL, "Identifikátor zařízení (devEUI)"),
                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ));

    public static final AttributeDescriptor<ValueType.ObjectMap> RAW_VALUE_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("rawValue", ValueType.JSON_OBJECT,
                    new MetaItem<>(MetaItemType.LABEL, "Surová data"),
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.RULE_STATE));

    public static final AttributeDescriptor<String> METER_ID_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("meter_id", ValueType.TEXT,
                    new MetaItem<>(MetaItemType.LABEL, "Výrobní číslo zařízení"),
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                    .withOptional(true);

    // Operator-provisioned routing/binding key: selects this asset's reading when a device
    // feeds several assets that claim the same field. Not READ_ONLY (the operator sets it),
    // not decoded (excluded in SameNameDecoder). Distinct from meter_id (the decoded serial).
    public static final AttributeDescriptor<String> EXTERNAL_ID_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("external_id", ValueType.TEXT,
                    new MetaItem<>(MetaItemType.LABEL, "Externí identifikátor"),
                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                    .withOptional(true);

    // -- Counts (people) ------------------------------------------------------

    public static final AttributeDescriptor<Integer> PERIOD_IN_ATTRIBUTE_DESCRIPTOR =
            count("period_in", "Příchody za interval");
    public static final AttributeDescriptor<Integer> PERIOD_OUT_ATTRIBUTE_DESCRIPTOR =
            count("period_out", "Odchody za interval");
    public static final AttributeDescriptor<Integer> TOTAL_IN_ATTRIBUTE_DESCRIPTOR =
            count("total_in", "Příchody celkem");
    public static final AttributeDescriptor<Integer> TOTAL_OUT_ATTRIBUTE_DESCRIPTOR =
            count("total_out", "Odchody celkem");

    // -- Device ---------------------------------------------------------------

    // Measured by the device itself; the high-temperature alarm below is about the device, so
    // this is not a room-climate reading.
    public static final AttributeDescriptor<Double> TEMPERATURE_ATTRIBUTE_DESCRIPTOR =
            measurement("temperature", "Teplota zařízení", Constants.UNITS_CELSIUS);

    // Optional; no STORE_DATA_POINTS / RULE_STATE, as on the meter types.
    public static final AttributeDescriptor<Integer> BATTERY_LEVEL_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("battery_level", ValueType.POSITIVE_INTEGER,
                    new MetaItem<>(MetaItemType.LABEL, "Stav baterie"),
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                    .withUnits(Constants.UNITS_PERCENTAGE)
                    .withOptional(true);

    // -- Alarms (boolean) -----------------------------------------------------

    public static final AttributeDescriptor<Boolean> PERIOD_COUNT_ALARM_ATTRIBUTE_DESCRIPTOR =
            alarm("period_count_alarm", "Překročen limit za interval");
    public static final AttributeDescriptor<Boolean> TOTAL_COUNT_ALARM_ATTRIBUTE_DESCRIPTOR =
            alarm("total_count_alarm", "Překročen celkový limit");
    public static final AttributeDescriptor<Boolean> TEMPERATURE_ALARM_ATTRIBUTE_DESCRIPTOR =
            alarm("temperature_alarm", "Teplotní alarm");
    public static final AttributeDescriptor<Boolean> HIGH_TEMPERATURE_ALARM_ATTRIBUTE_DESCRIPTOR =
            alarm("high_temperature_alarm", "Vysoká teplota");

    // Purple keeps the type distinct from the meter, tracker and RFID colours.
    public static final AssetDescriptor<PeopleCounterAsset> PEOPLE_COUNTER_ASSET_DESCRIPTOR =
            new AssetDescriptor<>("account-multiple", "7B1FA2", PeopleCounterAsset.class);

    // -- Descriptor factory helpers (measurement/alarm meta is identical everywhere) --

    // No units: OpenRemote has no unit for a count of people.
    private static AttributeDescriptor<Integer> count(String name, String label) {
        return new AttributeDescriptor<>(name, ValueType.POSITIVE_INTEGER,
                new MetaItem<>(MetaItemType.LABEL, label),
                new MetaItem<>(MetaItemType.READ_ONLY),
                new MetaItem<>(MetaItemType.RULE_STATE),
                new MetaItem<>(MetaItemType.STORE_DATA_POINTS),
                new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                .withOptional(true);
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

    private static AttributeDescriptor<Boolean> alarm(String name, String label) {
        return new AttributeDescriptor<Boolean>(name, ValueType.BOOLEAN,
                new MetaItem<>(MetaItemType.LABEL, label),
                new MetaItem<>(MetaItemType.READ_ONLY),
                new MetaItem<>(MetaItemType.RULE_STATE),
                new MetaItem<>(MetaItemType.STORE_DATA_POINTS),
                new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                .withOptional(true);
    }

    protected PeopleCounterAsset() {
        // For JPA/Jackson
    }

    public PeopleCounterAsset(String name) {
        super(name);
    }

    // -- Getters --------------------------------------------------------------

    public Optional<String> getDevEui() { return getAttributes().getValue(DEV_EUI_ATTRIBUTE_DESCRIPTOR); }
    public Optional<ValueType.ObjectMap> getRawValue() { return getAttributes().getValue(RAW_VALUE_ATTRIBUTE_DESCRIPTOR); }
    public Optional<String> getMeterId() { return getAttributes().getValue(METER_ID_ATTRIBUTE_DESCRIPTOR); }
    public Optional<String> getExternalId() { return getAttributes().getValue(EXTERNAL_ID_ATTRIBUTE_DESCRIPTOR); }

    public Optional<Integer> getPeriodIn() { return getAttributes().getValue(PERIOD_IN_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Integer> getPeriodOut() { return getAttributes().getValue(PERIOD_OUT_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Integer> getTotalIn() { return getAttributes().getValue(TOTAL_IN_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Integer> getTotalOut() { return getAttributes().getValue(TOTAL_OUT_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getTemperature() { return getAttributes().getValue(TEMPERATURE_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Integer> getBatteryLevel() { return getAttributes().getValue(BATTERY_LEVEL_ATTRIBUTE_DESCRIPTOR); }

    public Optional<Boolean> getPeriodCountAlarm() { return getAttributes().getValue(PERIOD_COUNT_ALARM_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getTotalCountAlarm() { return getAttributes().getValue(TOTAL_COUNT_ALARM_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getTemperatureAlarm() { return getAttributes().getValue(TEMPERATURE_ALARM_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Boolean> getHighTemperatureAlarm() { return getAttributes().getValue(HIGH_TEMPERATURE_ALARM_ATTRIBUTE_DESCRIPTOR); }
}
