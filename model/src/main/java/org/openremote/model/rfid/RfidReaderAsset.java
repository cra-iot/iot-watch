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
public class RfidReaderAsset extends Asset<RfidReaderAsset> {

    public enum RfidReaderStatus {
        ONLINE,
        OFFLINE,
        ERROR
    }

    public static final ValueDescriptor<RfidReaderStatus> STATUS_VALUE_DESCRIPTOR =
            new ValueDescriptor<>("rfidReaderStatus", RfidReaderStatus.class);

    // Platform device identifier (LoRa devEUI), matched by the HTTP ingest endpoint
    public static final AttributeDescriptor<String> DEV_EUI_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("devEui", ValueType.TEXT);

    public static final AttributeDescriptor<ValueType.ObjectMap> RAW_VALUE_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("rawValue", ValueType.JSON_OBJECT,
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.RULE_STATE));

    public static final AttributeDescriptor<String> READER_ID_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("readerId", ValueType.TEXT,
                    new MetaItem<>(MetaItemType.READ_ONLY));

    public static final AttributeDescriptor<RfidReaderStatus> STATUS_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("status", STATUS_VALUE_DESCRIPTOR,
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.STORE_DATA_POINTS));

    public static final AttributeDescriptor<Long> LAST_EVENT_TIME_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("lastEventTime", ValueType.TIMESTAMP,
                    new MetaItem<>(MetaItemType.READ_ONLY));

    public static final AttributeDescriptor<String> LAST_CHIP_ID_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("lastChipId", ValueType.TEXT,
                    new MetaItem<>(MetaItemType.READ_ONLY));

    public static final AttributeDescriptor<Boolean> TAMPER_ALARM_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("tamperAlarm", ValueType.BOOLEAN,
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.STORE_DATA_POINTS));

    public static final AssetDescriptor<RfidReaderAsset> RFID_READER_ASSET_DESCRIPTOR =
            new AssetDescriptor<>("access-point", "00838F", RfidReaderAsset.class);

    protected RfidReaderAsset() {
        // For JPA/Jackson
    }

    public RfidReaderAsset(String name) {
        super(name);
    }

    public Optional<String> getDevEui() {
        return getAttributes().getValue(DEV_EUI_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<ValueType.ObjectMap> getRawValue() {
        return getAttributes().getValue(RAW_VALUE_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<String> getReaderId() {
        return getAttributes().getValue(READER_ID_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<RfidReaderStatus> getStatus() {
        return getAttributes().getValue(STATUS_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<Long> getLastEventTime() {
        return getAttributes().getValue(LAST_EVENT_TIME_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<String> getLastChipId() {
        return getAttributes().getValue(LAST_CHIP_ID_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<Boolean> getTamperAlarm() {
        return getAttributes().getValue(TAMPER_ALARM_ATTRIBUTE_DESCRIPTOR);
    }
}
