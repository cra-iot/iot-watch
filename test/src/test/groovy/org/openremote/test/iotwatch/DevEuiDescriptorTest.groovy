package org.openremote.test.iotwatch

import org.openremote.model.rfid.RfidChipAsset
import org.openremote.model.rfid.RfidReaderAsset
import org.openremote.model.tracker.ShipTrackerAsset
import org.openremote.model.tracker.TrackerAsset
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
            RfidReaderAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR
        ]
    }
}
