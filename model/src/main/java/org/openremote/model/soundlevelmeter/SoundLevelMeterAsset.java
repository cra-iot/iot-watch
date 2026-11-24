package org.openremote.model.soundlevelmeter;

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
 * Sound level meter (noise sensor) asset. The model is device-agnostic; the first supported
 * device is the Milesight WS302.
 *
 * devEui + rawValue are required; every measurement is an optional descriptor the operator adds
 * per instance - a device reports only some of the levels. Attribute-name strings equal the
 * decoder's canonical snake_case keys, so SameNameDecoder needs no mapping table. The optional
 * meter_id carries the device serial: the name is shared with the meter types so the Fleet
 * page's identity column and search cover sound level meters without a type-specific case.
 *
 * Levels are in dB exactly as measured and are never converted. The weighting a level was
 * measured with travels in frequency_weighting (A/C/Z) and time_weighting (F/S/I). Both are
 * stored with history, so the history of a reconfigured device stays interpretable.
 * sound_level_eq is an equivalent (averaged) level and has no time weighting.
 *
 * See docs/sound-level-meter-dictionary.md for the key contract and docs/ingest-data-spec.md
 * for the device-agnostic envelope, reserved keys and failure signalling.
 */
@Entity
public class SoundLevelMeterAsset extends Asset<SoundLevelMeterAsset> {

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

    // -- Levels (dB) ----------------------------------------------------------

    public static final AttributeDescriptor<Double> SOUND_LEVEL_ATTRIBUTE_DESCRIPTOR =
            level("sound_level", "Okamžitá hladina hluku");
    public static final AttributeDescriptor<Double> SOUND_LEVEL_EQ_ATTRIBUTE_DESCRIPTOR =
            level("sound_level_eq", "Ekvivalentní hladina hluku");
    public static final AttributeDescriptor<Double> SOUND_LEVEL_MAX_ATTRIBUTE_DESCRIPTOR =
            level("sound_level_max", "Maximální hladina hluku");

    // -- Weightings -----------------------------------------------------------

    public static final AttributeDescriptor<String> FREQUENCY_WEIGHTING_ATTRIBUTE_DESCRIPTOR =
            weighting("frequency_weighting", "Frekvenční vážení");
    public static final AttributeDescriptor<String> TIME_WEIGHTING_ATTRIBUTE_DESCRIPTOR =
            weighting("time_weighting", "Časové vážení");

    // -- Device ---------------------------------------------------------------

    // Optional; no STORE_DATA_POINTS / RULE_STATE, as on the meter types.
    public static final AttributeDescriptor<Integer> BATTERY_LEVEL_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("battery_level", ValueType.POSITIVE_INTEGER,
                    new MetaItem<>(MetaItemType.LABEL, "Stav baterie"),
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                    .withUnits(Constants.UNITS_PERCENTAGE)
                    .withOptional(true);

    public static final AssetDescriptor<SoundLevelMeterAsset> SOUND_LEVEL_METER_ASSET_DESCRIPTOR =
            new AssetDescriptor<>("volume-high", "3949AB", SoundLevelMeterAsset.class);

    // -- Descriptor factory helpers (level/weighting meta is identical everywhere) --

    private static AttributeDescriptor<Double> level(String name, String label) {
        return new AttributeDescriptor<>(name, ValueType.NUMBER,
                new MetaItem<>(MetaItemType.LABEL, label),
                new MetaItem<>(MetaItemType.READ_ONLY),
                new MetaItem<>(MetaItemType.RULE_STATE),
                new MetaItem<>(MetaItemType.STORE_DATA_POINTS),
                new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                .withUnits(Constants.UNITS_DECIBEL)
                .withOptional(true);
    }

    // Text, no units; history is kept (see class Javadoc).
    private static AttributeDescriptor<String> weighting(String name, String label) {
        return new AttributeDescriptor<>(name, ValueType.TEXT,
                new MetaItem<>(MetaItemType.LABEL, label),
                new MetaItem<>(MetaItemType.READ_ONLY),
                new MetaItem<>(MetaItemType.RULE_STATE),
                new MetaItem<>(MetaItemType.STORE_DATA_POINTS),
                new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                .withOptional(true);
    }

    protected SoundLevelMeterAsset() {
        // For JPA/Jackson
    }

    public SoundLevelMeterAsset(String name) {
        super(name);
    }

    // -- Getters --------------------------------------------------------------

    public Optional<String> getDevEui() { return getAttributes().getValue(DEV_EUI_ATTRIBUTE_DESCRIPTOR); }
    public Optional<ValueType.ObjectMap> getRawValue() { return getAttributes().getValue(RAW_VALUE_ATTRIBUTE_DESCRIPTOR); }
    public Optional<String> getMeterId() { return getAttributes().getValue(METER_ID_ATTRIBUTE_DESCRIPTOR); }
    public Optional<String> getExternalId() { return getAttributes().getValue(EXTERNAL_ID_ATTRIBUTE_DESCRIPTOR); }

    public Optional<Double> getSoundLevel() { return getAttributes().getValue(SOUND_LEVEL_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getSoundLevelEq() { return getAttributes().getValue(SOUND_LEVEL_EQ_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Double> getSoundLevelMax() { return getAttributes().getValue(SOUND_LEVEL_MAX_ATTRIBUTE_DESCRIPTOR); }
    public Optional<String> getFrequencyWeighting() { return getAttributes().getValue(FREQUENCY_WEIGHTING_ATTRIBUTE_DESCRIPTOR); }
    public Optional<String> getTimeWeighting() { return getAttributes().getValue(TIME_WEIGHTING_ATTRIBUTE_DESCRIPTOR); }
    public Optional<Integer> getBatteryLevel() { return getAttributes().getValue(BATTERY_LEVEL_ATTRIBUTE_DESCRIPTOR); }
}
