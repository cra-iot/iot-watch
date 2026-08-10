package org.openremote.model.electricmeter;

import jakarta.persistence.Entity;

import java.util.Optional;

import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetDescriptor;
import org.openremote.model.attribute.MetaItem;
import org.openremote.model.value.AttributeDescriptor;
import org.openremote.model.value.MetaItemType;
import org.openremote.model.value.ValueType;

/**
 * A LoRaWAN concentrator/gateway that reads several wired electricity meters and
 * uploads them in one message. It is the single HTTP-ingest target for its devEUI;
 * the meters it reads are {@link ElectricMeterAsset} child assets, populated by the
 * electric-meter-gateway-decode Groovy rule.
 */
@Entity
public class ElectricMeterGatewayAsset extends Asset<ElectricMeterGatewayAsset> {

    // Platform device identifier (LoRa devEUI), matched by the HTTP ingest endpoint
    public static final AttributeDescriptor<String> DEV_EUI_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("devEui", ValueType.TEXT,
                    new MetaItem<>(MetaItemType.LABEL, "Identifikátor zařízení (devEUI)"));

    public static final AttributeDescriptor<ValueType.ObjectMap> RAW_VALUE_ATTRIBUTE_DESCRIPTOR =
            new AttributeDescriptor<>("rawValue", ValueType.JSON_OBJECT,
                    new MetaItem<>(MetaItemType.LABEL, "Surová data"),
                    new MetaItem<>(MetaItemType.READ_ONLY),
                    new MetaItem<>(MetaItemType.RULE_STATE));

    public static final AssetDescriptor<ElectricMeterGatewayAsset> ELECTRIC_METER_GATEWAY_ASSET_DESCRIPTOR =
            new AssetDescriptor<>("router-wireless", "607D8B", ElectricMeterGatewayAsset.class);

    protected ElectricMeterGatewayAsset() {
        // For JPA/Jackson
    }

    public ElectricMeterGatewayAsset(String name) {
        super(name);
    }

    public Optional<String> getDevEui() {
        return getAttributes().getValue(DEV_EUI_ATTRIBUTE_DESCRIPTOR);
    }

    public Optional<ValueType.ObjectMap> getRawValue() {
        return getAttributes().getValue(RAW_VALUE_ATTRIBUTE_DESCRIPTOR);
    }
}
