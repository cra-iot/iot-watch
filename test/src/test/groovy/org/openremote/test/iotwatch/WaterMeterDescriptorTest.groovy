package org.openremote.test.iotwatch

import org.openremote.model.watermeter.WaterMeterAsset
import org.openremote.model.value.ValueType
import spock.lang.Specification

class WaterMeterDescriptorTest extends Specification {

    def "meter requires devEui, rawValue and currentReading; identity + extras optional"() {
        expect:
        !WaterMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR.isOptional()
        WaterMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR.type == ValueType.TEXT
        !WaterMeterAsset.RAW_VALUE_ATTRIBUTE_DESCRIPTOR.isOptional()
        WaterMeterAsset.RAW_VALUE_ATTRIBUTE_DESCRIPTOR.type == ValueType.JSON_OBJECT
        !WaterMeterAsset.CURRENT_READING_ATTRIBUTE_DESCRIPTOR.isOptional()
        WaterMeterAsset.CURRENT_READING_ATTRIBUTE_DESCRIPTOR.name == "currentReading"
        WaterMeterAsset.CURRENT_READING_ATTRIBUTE_DESCRIPTOR.type == ValueType.NUMBER
        WaterMeterAsset.METER_ID_ATTRIBUTE_DESCRIPTOR.isOptional()
        WaterMeterAsset.METER_ID_ATTRIBUTE_DESCRIPTOR.name == "meter_id"
        WaterMeterAsset.METER_ID_ATTRIBUTE_DESCRIPTOR.type == ValueType.TEXT
    }

    def "measurement '#descriptor.name' is optional with the expected key and type"() {
        expect:
        descriptor.isOptional()
        descriptor.name == key
        descriptor.type == type

        where:
        descriptor                                                | key                   | type
        WaterMeterAsset.VOLUME_REVERSE_ATTRIBUTE_DESCRIPTOR       | "volume_reverse"      | ValueType.NUMBER
        WaterMeterAsset.VOLUME_PREVIOUS_DAY_ATTRIBUTE_DESCRIPTOR  | "volume_previous_day" | ValueType.NUMBER
        WaterMeterAsset.FLOW_RATE_ATTRIBUTE_DESCRIPTOR            | "flow_rate"           | ValueType.NUMBER
        WaterMeterAsset.WATER_TEMPERATURE_ATTRIBUTE_DESCRIPTOR    | "water_temperature"   | ValueType.NUMBER
        WaterMeterAsset.TEMPERATURE_MIN_ATTRIBUTE_DESCRIPTOR      | "temperature_min"     | ValueType.NUMBER
        WaterMeterAsset.TEMPERATURE_MAX_ATTRIBUTE_DESCRIPTOR      | "temperature_max"     | ValueType.NUMBER
        WaterMeterAsset.BATTERY_LEVEL_ATTRIBUTE_DESCRIPTOR        | "battery_level"       | ValueType.POSITIVE_INTEGER
    }

    def "alarm '#descriptor.name' is an optional boolean with the expected snake_case key"() {
        expect:
        descriptor.isOptional()
        descriptor.name == key
        descriptor.type == ValueType.BOOLEAN

        where:
        descriptor                                                | key
        WaterMeterAsset.LEAKAGE_ATTRIBUTE_DESCRIPTOR              | "leakage"
        WaterMeterAsset.BURST_ATTRIBUTE_DESCRIPTOR               | "burst"
        WaterMeterAsset.OVERFLOW_ATTRIBUTE_DESCRIPTOR            | "overflow"
        WaterMeterAsset.INVERSE_FLOW_ATTRIBUTE_DESCRIPTOR        | "inverse_flow"
        WaterMeterAsset.TAMPER_ATTRIBUTE_DESCRIPTOR             | "tamper"
        WaterMeterAsset.REVERSE_INSTALLATION_ATTRIBUTE_DESCRIPTOR| "reverse_installation"
        WaterMeterAsset.LOW_BATTERY_ATTRIBUTE_DESCRIPTOR        | "low_battery"
        WaterMeterAsset.MEASURE_BOARD_ERROR_ATTRIBUTE_DESCRIPTOR | "measure_board_error"
        WaterMeterAsset.FREEZING_ATTRIBUTE_DESCRIPTOR           | "freezing"
        WaterMeterAsset.NO_WATER_ATTRIBUTE_DESCRIPTOR           | "no_water"
    }
}
