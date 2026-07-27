package org.openremote.model.watermeter;

import jakarta.persistence.Entity;

import java.util.Optional;

import org.openremote.model.Constants;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetDescriptor;
import org.openremote.model.attribute.MetaItem;
import org.openremote.model.value.AttributeDescriptor;
import org.openremote.model.value.MetaItemType;
import org.openremote.model.value.ValueDescriptor;
import org.openremote.model.value.ValueType;

@Entity
public class WaterMeterAsset extends Asset<WaterMeterAsset> {

    public enum WaterMeterStatus {
        OK,
        WARNING,
        FAULT
    }


    public static final ValueDescriptor<WaterMeterStatus> STATUS_VALUE_DESCRIPTOR =
            new ValueDescriptor<>("waterMeterStatus", WaterMeterStatus.class);

    // Platform device identifier (LoRa devEUI), matched by the HTTP ingest endpoint
    public static final AttributeDescriptor<String> DEV_EUI_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("devEui", ValueType.TEXT);

    public static final AttributeDescriptor<ValueType.ObjectMap> RAW_VALUE_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("rawValue", ValueType.JSON_OBJECT,
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.RULE_STATE));

    public static final AttributeDescriptor<Double> CURRENT_READING_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("currentReading", ValueType.NUMBER,
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.STORE_DATA_POINTS))
                    .withUnits(Constants.UNITS_METRE, Constants.UNITS_CUBED);

    public static final AttributeDescriptor<Double> CONSUMPTION_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("consumption", ValueType.NUMBER,
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.STORE_DATA_POINTS))
                    .withUnits(Constants.UNITS_METRE, Constants.UNITS_CUBED);

    public static final AttributeDescriptor<Double> REVERSE_FLOW_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("reverseFlow", ValueType.NUMBER,
                    new MetaItem<>(MetaItemType.READ_ONLY));

    public static final AttributeDescriptor<WaterMeterStatus> STATUS_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("status", STATUS_VALUE_DESCRIPTOR,
                    new MetaItem<>(MetaItemType.READ_ONLY));

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


    public Optional<Double> getConsumption() {
        return getAttributes().getValue(CONSUMPTION_ATTRIBUTE_DESCRIPTOR);
    }


    public Optional<Double> getReverseFlow() {
        return getAttributes().getValue(REVERSE_FLOW_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<WaterMeterStatus> getStatus() {
        return getAttributes().getValue(STATUS_ATTRIBUTE_DESCRIPTOR);
    }

}
