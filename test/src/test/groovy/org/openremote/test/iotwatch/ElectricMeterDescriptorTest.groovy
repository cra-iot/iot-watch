package org.openremote.test.iotwatch

import org.openremote.model.electricmeter.ElectricMeterAsset
import org.openremote.model.value.ValueType
import spock.lang.Specification

class ElectricMeterDescriptorTest extends Specification {

    def "meter requires devEui and rawValue; identity + measurements optional"() {
        expect:
        !ElectricMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR.isOptional()
        !ElectricMeterAsset.RAW_VALUE_ATTRIBUTE_DESCRIPTOR.isOptional()
        ElectricMeterAsset.METER_ID_ATTRIBUTE_DESCRIPTOR.isOptional()
        ElectricMeterAsset.METER_ID_ATTRIBUTE_DESCRIPTOR.name == "meter_id"
        ElectricMeterAsset.METER_ID_ATTRIBUTE_DESCRIPTOR.type == ValueType.TEXT
    }

    def "measurement '#descriptor.name' is optional with the expected key and type"() {
        expect:
        descriptor.isOptional()
        descriptor.name == key
        descriptor.type == type

        where:
        descriptor                                                    | key                    | type
        ElectricMeterAsset.ENERGY_ACTIVE_TOTAL_ATTRIBUTE_DESCRIPTOR   | "energy_active_total"  | ValueType.NUMBER
        ElectricMeterAsset.ENERGY_ACTIVE_T1_ATTRIBUTE_DESCRIPTOR      | "energy_active_t1"     | ValueType.NUMBER
        ElectricMeterAsset.ENERGY_ACTIVE_T2_ATTRIBUTE_DESCRIPTOR      | "energy_active_t2"     | ValueType.NUMBER
        ElectricMeterAsset.ENERGY_REACTIVE_ATTRIBUTE_DESCRIPTOR       | "energy_reactive"      | ValueType.NUMBER
        ElectricMeterAsset.ENERGY_REACTIVE_1_ATTRIBUTE_DESCRIPTOR     | "energy_reactive_1"    | ValueType.NUMBER
        ElectricMeterAsset.ENERGY_REACTIVE_2_ATTRIBUTE_DESCRIPTOR     | "energy_reactive_2"    | ValueType.NUMBER
        ElectricMeterAsset.ENERGY_REACTIVE_3_ATTRIBUTE_DESCRIPTOR     | "energy_reactive_3"    | ValueType.NUMBER
        ElectricMeterAsset.POWER_TOTAL_ATTRIBUTE_DESCRIPTOR           | "power_total"          | ValueType.NUMBER
        ElectricMeterAsset.POWER_1_ATTRIBUTE_DESCRIPTOR               | "power_1"              | ValueType.NUMBER
        ElectricMeterAsset.POWER_2_ATTRIBUTE_DESCRIPTOR               | "power_2"              | ValueType.NUMBER
        ElectricMeterAsset.POWER_3_ATTRIBUTE_DESCRIPTOR               | "power_3"              | ValueType.NUMBER
        ElectricMeterAsset.POWER_APPARENT_ATTRIBUTE_DESCRIPTOR        | "power_apparent"       | ValueType.NUMBER
        ElectricMeterAsset.POWER_FACTOR_ATTRIBUTE_DESCRIPTOR          | "power_factor"         | ValueType.NUMBER
        ElectricMeterAsset.FREQUENCY_ATTRIBUTE_DESCRIPTOR             | "frequency"            | ValueType.NUMBER
        ElectricMeterAsset.VOLTAGE_1_ATTRIBUTE_DESCRIPTOR             | "voltage_1"            | ValueType.NUMBER
        ElectricMeterAsset.VOLTAGE_2_ATTRIBUTE_DESCRIPTOR             | "voltage_2"            | ValueType.NUMBER
        ElectricMeterAsset.VOLTAGE_3_ATTRIBUTE_DESCRIPTOR             | "voltage_3"            | ValueType.NUMBER
        ElectricMeterAsset.CURRENT_1_ATTRIBUTE_DESCRIPTOR             | "current_1"            | ValueType.NUMBER
        ElectricMeterAsset.CURRENT_2_ATTRIBUTE_DESCRIPTOR             | "current_2"            | ValueType.NUMBER
        ElectricMeterAsset.CURRENT_3_ATTRIBUTE_DESCRIPTOR             | "current_3"            | ValueType.NUMBER
        ElectricMeterAsset.TEMPERATURE_1_ATTRIBUTE_DESCRIPTOR         | "temperature_1"        | ValueType.NUMBER
        ElectricMeterAsset.TEMPERATURE_2_ATTRIBUTE_DESCRIPTOR         | "temperature_2"        | ValueType.NUMBER
        ElectricMeterAsset.TEMPERATURE_3_ATTRIBUTE_DESCRIPTOR         | "temperature_3"        | ValueType.NUMBER
        ElectricMeterAsset.TEMPERATURE_4_ATTRIBUTE_DESCRIPTOR         | "temperature_4"        | ValueType.NUMBER
        ElectricMeterAsset.TARIFF_ATTRIBUTE_DESCRIPTOR                | "tariff"               | ValueType.POSITIVE_INTEGER
    }
}
