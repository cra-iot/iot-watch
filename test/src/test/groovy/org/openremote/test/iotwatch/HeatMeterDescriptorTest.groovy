package org.openremote.test.iotwatch

import org.openremote.model.heatmeter.HeatMeterAsset
import org.openremote.model.value.ValueType
import spock.lang.Specification

class HeatMeterDescriptorTest extends Specification {

    def "meter requires devEui and rawValue; identity + measurements optional"() {
        expect:
        !HeatMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR.isOptional()
        HeatMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR.type == ValueType.TEXT
        !HeatMeterAsset.RAW_VALUE_ATTRIBUTE_DESCRIPTOR.isOptional()
        HeatMeterAsset.RAW_VALUE_ATTRIBUTE_DESCRIPTOR.type == ValueType.JSON_OBJECT
        HeatMeterAsset.METER_ID_ATTRIBUTE_DESCRIPTOR.isOptional()
        HeatMeterAsset.METER_ID_ATTRIBUTE_DESCRIPTOR.name == "meter_id"
        HeatMeterAsset.METER_ID_ATTRIBUTE_DESCRIPTOR.type == ValueType.TEXT
        HeatMeterAsset.EXTERNAL_ID_ATTRIBUTE_DESCRIPTOR.isOptional()
        HeatMeterAsset.EXTERNAL_ID_ATTRIBUTE_DESCRIPTOR.name == "external_id"
        HeatMeterAsset.EXTERNAL_ID_ATTRIBUTE_DESCRIPTOR.type == ValueType.TEXT
    }

    def "measurement '#descriptor.name' is optional with the expected key and type"() {
        expect:
        descriptor.isOptional()
        descriptor.name == key
        descriptor.type == type

        where:
        descriptor                                                          | key                             | type
        HeatMeterAsset.ENERGY_HEATING_ATTRIBUTE_DESCRIPTOR                  | "energy_heating"                | ValueType.NUMBER
        HeatMeterAsset.ENERGY_COOLING_ATTRIBUTE_DESCRIPTOR                  | "energy_cooling"                | ValueType.NUMBER
        HeatMeterAsset.ENERGY_HEATING_PREVIOUS_MONTH_ATTRIBUTE_DESCRIPTOR   | "energy_heating_previous_month" | ValueType.NUMBER
        HeatMeterAsset.ENERGY_HEATING_PREVIOUS_DAY_ATTRIBUTE_DESCRIPTOR     | "energy_heating_previous_day"   | ValueType.NUMBER
        HeatMeterAsset.ENERGY_TARIFF_1_ATTRIBUTE_DESCRIPTOR                 | "energy_tariff_1"               | ValueType.NUMBER
        HeatMeterAsset.ENERGY_TARIFF_2_ATTRIBUTE_DESCRIPTOR                 | "energy_tariff_2"               | ValueType.NUMBER
        HeatMeterAsset.VOLUME_ATTRIBUTE_DESCRIPTOR                          | "volume"                        | ValueType.NUMBER
        HeatMeterAsset.VOLUME_PREVIOUS_MONTH_ATTRIBUTE_DESCRIPTOR           | "volume_previous_month"         | ValueType.NUMBER
        HeatMeterAsset.POWER_ATTRIBUTE_DESCRIPTOR                           | "power"                         | ValueType.NUMBER
        HeatMeterAsset.POWER_MAX_ATTRIBUTE_DESCRIPTOR                       | "power_max"                     | ValueType.NUMBER
        HeatMeterAsset.FLOW_RATE_ATTRIBUTE_DESCRIPTOR                       | "flow_rate"                     | ValueType.NUMBER
        HeatMeterAsset.FLOW_RATE_MAX_ATTRIBUTE_DESCRIPTOR                   | "flow_rate_max"                 | ValueType.NUMBER
        HeatMeterAsset.TEMPERATURE_FLOW_ATTRIBUTE_DESCRIPTOR                | "temperature_flow"              | ValueType.NUMBER
        HeatMeterAsset.TEMPERATURE_RETURN_ATTRIBUTE_DESCRIPTOR              | "temperature_return"            | ValueType.NUMBER
        HeatMeterAsset.TEMPERATURE_DIFFERENCE_ATTRIBUTE_DESCRIPTOR          | "temperature_difference"        | ValueType.NUMBER
        HeatMeterAsset.OPERATING_HOURS_ATTRIBUTE_DESCRIPTOR                 | "operating_hours"               | ValueType.NUMBER
        HeatMeterAsset.ERROR_HOURS_ATTRIBUTE_DESCRIPTOR                     | "error_hours"                   | ValueType.NUMBER
        HeatMeterAsset.BATTERY_LEVEL_ATTRIBUTE_DESCRIPTOR                   | "battery_level"                 | ValueType.POSITIVE_INTEGER
        HeatMeterAsset.ERROR_FLAGS_ATTRIBUTE_DESCRIPTOR                     | "error_flags"                   | ValueType.TEXT
    }

    def "alarm '#descriptor.name' is an optional boolean with the expected snake_case key"() {
        expect:
        descriptor.isOptional()
        descriptor.name == key
        descriptor.type == ValueType.BOOLEAN

        where:
        descriptor                                                     | key
        HeatMeterAsset.METER_ERROR_ATTRIBUTE_DESCRIPTOR                | "meter_error"
        HeatMeterAsset.METER_READ_ERROR_ATTRIBUTE_DESCRIPTOR           | "meter_read_error"
        HeatMeterAsset.LOW_BATTERY_ATTRIBUTE_DESCRIPTOR                | "low_battery"
        HeatMeterAsset.TAMPER_ATTRIBUTE_DESCRIPTOR                     | "tamper"
        HeatMeterAsset.LEAKAGE_ATTRIBUTE_DESCRIPTOR                    | "leakage"
        HeatMeterAsset.BURST_ATTRIBUTE_DESCRIPTOR                      | "burst"
        HeatMeterAsset.INVERSE_FLOW_ATTRIBUTE_DESCRIPTOR               | "inverse_flow"
        HeatMeterAsset.FREEZING_ATTRIBUTE_DESCRIPTOR                   | "freezing"
        HeatMeterAsset.MEASURE_BOARD_ERROR_ATTRIBUTE_DESCRIPTOR        | "measure_board_error"
        HeatMeterAsset.NO_FLOW_ATTRIBUTE_DESCRIPTOR                    | "no_flow"
        HeatMeterAsset.AIR_IN_PIPE_ATTRIBUTE_DESCRIPTOR                | "air_in_pipe"
        HeatMeterAsset.TEMPERATURE_SENSOR_ERROR_ATTRIBUTE_DESCRIPTOR   | "temperature_sensor_error"
        HeatMeterAsset.FLOW_SENSOR_ERROR_ATTRIBUTE_DESCRIPTOR          | "flow_sensor_error"
    }

    // The heat meter keeps no camelCase exception: unlike WaterMeterAsset's currentReading,
    // nothing here predates the snake_case convention, and its primary index is energy.
    def "declares no currentReading and every measurement key is snake_case"() {
        given:
        def names = HeatMeterAsset.declaredFields
            .findAll { it.name.endsWith("_ATTRIBUTE_DESCRIPTOR") }
            .collect { it.get(null).name }

        expect:
        !names.contains("currentReading")
        names.contains("energy_heating")
        // devEui and rawValue are the ingest-contract names; all the rest are snake_case.
        names.findAll { !(it in ["devEui", "rawValue"]) }.every { it ==~ /^[a-z0-9]+(_[a-z0-9]+)*$/ }
    }
}
