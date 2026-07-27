package org.openremote.model.rfid;

import jakarta.persistence.Entity;

import java.util.Optional;

import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetDescriptor;
import org.openremote.model.attribute.MetaItem;
import org.openremote.model.value.AttributeDescriptor;
import org.openremote.model.value.MetaItemType;
import org.openremote.model.value.ValueDescriptor;
import org.openremote.model.value.ValueType;

@Entity
public class RfidChipAsset extends Asset<RfidChipAsset> {

    public enum RfidChipStatus {
        ENTRY,
        LEAVE,
        OK,
        SERVICE
    }

    public static final ValueDescriptor<RfidChipStatus> STATUS_VALUE_DESCRIPTOR =
            new ValueDescriptor<>("rfidChipStatus", RfidChipStatus.class);

    // Platform device identifier (LoRa devEUI), matched by the HTTP ingest endpoint
    public static final AttributeDescriptor<String> DEV_EUI_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("devEui", ValueType.TEXT);

    public static final AttributeDescriptor<ValueType.ObjectMap> RAW_VALUE_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("rawValue", ValueType.JSON_OBJECT,
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.RULE_STATE));

    public static final AttributeDescriptor<String> CHIP_ID_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("chipId", ValueType.TEXT,
                    new MetaItem<>(MetaItemType.READ_ONLY));

    public static final AttributeDescriptor<RfidChipStatus> STATUS_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("status", STATUS_VALUE_DESCRIPTOR,
                    new MetaItem<>(MetaItemType.READ_ONLY));

    public static final AttributeDescriptor<Long> EVENT_TIME_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("eventTime", ValueType.TIMESTAMP,
                    new MetaItem<>(MetaItemType.READ_ONLY));

    public static final AttributeDescriptor<String> READER_ID_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("readerId", ValueType.TEXT,
                    new MetaItem<>(MetaItemType.READ_ONLY));

    public static final AssetDescriptor<RfidChipAsset> RFID_CHIP_ASSET_DESCRIPTOR =
            new AssetDescriptor<>("nfc", "6A1B9A", RfidChipAsset.class);

    protected RfidChipAsset() {
        // For JPA/Jackson
    }

    public RfidChipAsset(String name) {
        super(name);
    }

    public Optional<String> getDevEui() {
        return getAttributes().getValue(DEV_EUI_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<ValueType.ObjectMap> getRawValue() {
        return getAttributes().getValue(RAW_VALUE_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<String> getChipId() {
        return getAttributes().getValue(CHIP_ID_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<RfidChipStatus> getStatus() {
        return getAttributes().getValue(STATUS_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<Long> getEventTime() {
        return getAttributes().getValue(EVENT_TIME_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<String> getReaderId() {
        return getAttributes().getValue(READER_ID_ATTRIBUTE_DESCRIPTOR);
    }
}
