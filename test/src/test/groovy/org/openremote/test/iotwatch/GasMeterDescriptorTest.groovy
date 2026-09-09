package org.openremote.test.iotwatch

import org.openremote.model.gasmeter.GasMeterAsset
import org.openremote.model.value.ValueType
import spock.lang.Specification

class GasMeterDescriptorTest extends Specification {

    def "meter requires devEui and rawValue; identity + measurements optional"() {
        expect:
        !GasMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR.isOptional()
        GasMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR.type == ValueType.TEXT
        !GasMeterAsset.RAW_VALUE_ATTRIBUTE_DESCRIPTOR.isOptional()
        GasMeterAsset.RAW_VALUE_ATTRIBUTE_DESCRIPTOR.type == ValueType.JSON_OBJECT
        GasMeterAsset.METER_ID_ATTRIBUTE_DESCRIPTOR.isOptional()
        GasMeterAsset.METER_ID_ATTRIBUTE_DESCRIPTOR.name == "meter_id"
        GasMeterAsset.METER_ID_ATTRIBUTE_DESCRIPTOR.type == ValueType.TEXT
        GasMeterAsset.EXTERNAL_ID_ATTRIBUTE_DESCRIPTOR.isOptional()
        GasMeterAsset.EXTERNAL_ID_ATTRIBUTE_DESCRIPTOR.name == "external_id"
        GasMeterAsset.EXTERNAL_ID_ATTRIBUTE_DESCRIPTOR.type == ValueType.TEXT
    }

    def "measurement '#descriptor.name' is optional with the expected key and type"() {
        expect:
        descriptor.isOptional()
        descriptor.name == key
        descriptor.type == type

        where:
        descriptor                                                          | key                                | type
        GasMeterAsset.VOLUME_ATTRIBUTE_DESCRIPTOR                           | "volume"                           | ValueType.NUMBER
        GasMeterAsset.VOLUME_PREVIOUS_MONTH_ATTRIBUTE_DESCRIPTOR            | "volume_previous_month"            | ValueType.NUMBER
        GasMeterAsset.VOLUME_PREVIOUS_DAY_ATTRIBUTE_DESCRIPTOR              | "volume_previous_day"              | ValueType.NUMBER
        GasMeterAsset.VOLUME_STANDARD_ATTRIBUTE_DESCRIPTOR                  | "volume_standard"                  | ValueType.NUMBER
        GasMeterAsset.VOLUME_STANDARD_PREVIOUS_MONTH_ATTRIBUTE_DESCRIPTOR   | "volume_standard_previous_month"   | ValueType.NUMBER
        GasMeterAsset.ENERGY_ATTRIBUTE_DESCRIPTOR                           | "energy"                           | ValueType.NUMBER
        GasMeterAsset.ENERGY_PREVIOUS_MONTH_ATTRIBUTE_DESCRIPTOR            | "energy_previous_month"            | ValueType.NUMBER
        GasMeterAsset.FLOW_RATE_ATTRIBUTE_DESCRIPTOR                        | "flow_rate"                        | ValueType.NUMBER
        GasMeterAsset.FLOW_RATE_MAX_ATTRIBUTE_DESCRIPTOR                    | "flow_rate_max"                    | ValueType.NUMBER
        GasMeterAsset.PRESSURE_ATTRIBUTE_DESCRIPTOR                         | "pressure"                         | ValueType.NUMBER
        GasMeterAsset.GAS_TEMPERATURE_ATTRIBUTE_DESCRIPTOR                  | "gas_temperature"                  | ValueType.NUMBER
        GasMeterAsset.BATTERY_LEVEL_ATTRIBUTE_DESCRIPTOR                    | "battery_level"                    | ValueType.POSITIVE_INTEGER
        GasMeterAsset.ERROR_FLAGS_ATTRIBUTE_DESCRIPTOR                      | "error_flags"                      | ValueType.TEXT
    }

    def "alarm '#descriptor.name' is an optional boolean with the expected snake_case key"() {
        expect:
        descriptor.isOptional()
        descriptor.name == key
        descriptor.type == ValueType.BOOLEAN

        where:
        descriptor                                                    | key
        GasMeterAsset.METER_ERROR_ATTRIBUTE_DESCRIPTOR                | "meter_error"
        GasMeterAsset.METER_READ_ERROR_ATTRIBUTE_DESCRIPTOR           | "meter_read_error"
        GasMeterAsset.LOW_BATTERY_ATTRIBUTE_DESCRIPTOR                | "low_battery"
        GasMeterAsset.TAMPER_ATTRIBUTE_DESCRIPTOR                     | "tamper"
        GasMeterAsset.LEAKAGE_ATTRIBUTE_DESCRIPTOR                    | "leakage"
        GasMeterAsset.INVERSE_FLOW_ATTRIBUTE_DESCRIPTOR               | "inverse_flow"
        GasMeterAsset.NO_FLOW_ATTRIBUTE_DESCRIPTOR                    | "no_flow"
        GasMeterAsset.MEASURE_BOARD_ERROR_ATTRIBUTE_DESCRIPTOR        | "measure_board_error"
        GasMeterAsset.TEMPERATURE_SENSOR_ERROR_ATTRIBUTE_DESCRIPTOR   | "temperature_sensor_error"
        GasMeterAsset.PRESSURE_SENSOR_ERROR_ATTRIBUTE_DESCRIPTOR      | "pressure_sensor_error"
        GasMeterAsset.VALVE_CLOSED_ATTRIBUTE_DESCRIPTOR               | "valve_closed"
    }

    // Like the heat meter, the gas meter keeps no camelCase exception: nothing here predates
    // the snake_case convention, so its cumulative index is volume, not currentReading.
    def "declares no currentReading and every measurement key is snake_case"() {
        given:
        def names = GasMeterAsset.declaredFields
            .findAll { it.name.endsWith("_ATTRIBUTE_DESCRIPTOR") }
            .collect { it.get(null).name }

        expect:
        !names.contains("currentReading")
        names.contains("volume")
        // devEui and rawValue are the ingest-contract names; all the rest are snake_case.
        names.findAll { !(it in ["devEui", "rawValue"]) }.every { it ==~ /^[a-z0-9]+(_[a-z0-9]+)*$/ }
    }
}
