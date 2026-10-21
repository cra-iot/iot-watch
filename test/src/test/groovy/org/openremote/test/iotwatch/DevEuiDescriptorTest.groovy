package org.openremote.test.iotwatch

import org.openremote.model.electricmeter.ElectricMeterAsset
import org.openremote.model.gasmeter.GasMeterAsset
import org.openremote.model.heatmeter.HeatMeterAsset
import org.openremote.model.rfid.RfidChipAsset
import org.openremote.model.rfid.RfidReaderAsset
import org.openremote.model.tracker.ShipTrackerAsset
import org.openremote.model.tracker.TrackerAsset
import org.openremote.model.value.MetaItemType
import org.openremote.model.value.ValueType
import org.openremote.model.watermeter.WaterMeterAsset
import spock.lang.Specification

class DevEuiDescriptorTest extends Specification {

    def "every IoT Watch asset type declares a required devEui attribute"() {
        expect:
        descriptor.name == "devEui"
        descriptor.type == ValueType.TEXT
        !descriptor.isOptional()

        where:
        descriptor << [
            TrackerAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR,
            ShipTrackerAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR,
            WaterMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR,
            RfidChipAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR,
            RfidReaderAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR,
            ElectricMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR,
            HeatMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR,
            GasMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR
        ]
    }

    def "devEui is readable by restricted users on every type"() {
        expect:
        descriptor.getMeta().has(MetaItemType.ACCESS_RESTRICTED_READ)

        where:
        descriptor << [
            TrackerAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR,
            ShipTrackerAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR,
            WaterMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR,
            RfidChipAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR,
            RfidReaderAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR,
            ElectricMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR,
            HeatMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR,
            GasMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR
        ]
    }

    def "rawValue stays hidden from restricted users on every type"() {
        expect:
        !descriptor.getMeta().has(MetaItemType.ACCESS_RESTRICTED_READ)

        where:
        descriptor << [
            TrackerAsset.RAW_VALUE_ATTRIBUTE_DESCRIPTOR,
            ShipTrackerAsset.RAW_VALUE_ATTRIBUTE_DESCRIPTOR,
            WaterMeterAsset.RAW_VALUE_ATTRIBUTE_DESCRIPTOR,
            RfidChipAsset.RAW_VALUE_ATTRIBUTE_DESCRIPTOR,
            RfidReaderAsset.RAW_VALUE_ATTRIBUTE_DESCRIPTOR,
            ElectricMeterAsset.RAW_VALUE_ATTRIBUTE_DESCRIPTOR,
            HeatMeterAsset.RAW_VALUE_ATTRIBUTE_DESCRIPTOR,
            GasMeterAsset.RAW_VALUE_ATTRIBUTE_DESCRIPTOR
        ]
    }
}
