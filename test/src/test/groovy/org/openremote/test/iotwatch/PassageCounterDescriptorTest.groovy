package org.openremote.test.iotwatch

import org.openremote.model.Constants
import org.openremote.model.passagecounter.PassageCounterAsset
import org.openremote.model.value.MetaItemType
import org.openremote.model.value.ValueType
import spock.lang.Specification

class PassageCounterDescriptorTest extends Specification {

    def "counter requires devEui and rawValue; identity + measurements optional"() {
        expect:
        !PassageCounterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR.isOptional()
        PassageCounterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR.type == ValueType.TEXT
        !PassageCounterAsset.RAW_VALUE_ATTRIBUTE_DESCRIPTOR.isOptional()
        PassageCounterAsset.RAW_VALUE_ATTRIBUTE_DESCRIPTOR.type == ValueType.JSON_OBJECT
        PassageCounterAsset.METER_ID_ATTRIBUTE_DESCRIPTOR.isOptional()
        PassageCounterAsset.METER_ID_ATTRIBUTE_DESCRIPTOR.name == "meter_id"
        PassageCounterAsset.METER_ID_ATTRIBUTE_DESCRIPTOR.type == ValueType.TEXT
        PassageCounterAsset.EXTERNAL_ID_ATTRIBUTE_DESCRIPTOR.isOptional()
        PassageCounterAsset.EXTERNAL_ID_ATTRIBUTE_DESCRIPTOR.name == "external_id"
        PassageCounterAsset.EXTERNAL_ID_ATTRIBUTE_DESCRIPTOR.type == ValueType.TEXT
    }

    def "measurement '#descriptor.name' is optional with the expected key and type"() {
        expect:
        descriptor.isOptional()
        descriptor.name == key
        descriptor.type == type

        where:
        descriptor                                                | key             | type
        PassageCounterAsset.PERIOD_IN_ATTRIBUTE_DESCRIPTOR        | "period_in"     | ValueType.POSITIVE_INTEGER
        PassageCounterAsset.PERIOD_OUT_ATTRIBUTE_DESCRIPTOR       | "period_out"    | ValueType.POSITIVE_INTEGER
        PassageCounterAsset.TOTAL_IN_ATTRIBUTE_DESCRIPTOR         | "total_in"      | ValueType.POSITIVE_INTEGER
        PassageCounterAsset.TOTAL_OUT_ATTRIBUTE_DESCRIPTOR        | "total_out"     | ValueType.POSITIVE_INTEGER
        PassageCounterAsset.TEMPERATURE_ATTRIBUTE_DESCRIPTOR      | "temperature"   | ValueType.NUMBER
        PassageCounterAsset.BATTERY_LEVEL_ATTRIBUTE_DESCRIPTOR    | "battery_level" | ValueType.POSITIVE_INTEGER
    }

    def "alarm '#descriptor.name' is an optional boolean with the expected snake_case key"() {
        expect:
        descriptor.isOptional()
        descriptor.name == key
        descriptor.type == ValueType.BOOLEAN

        where:
        descriptor                                                        | key
        PassageCounterAsset.PERIOD_COUNT_ALARM_ATTRIBUTE_DESCRIPTOR       | "period_count_alarm"
        PassageCounterAsset.TOTAL_COUNT_ALARM_ATTRIBUTE_DESCRIPTOR        | "total_count_alarm"
        PassageCounterAsset.TEMPERATURE_ALARM_ATTRIBUTE_DESCRIPTOR        | "temperature_alarm"
        PassageCounterAsset.HIGH_TEMPERATURE_ALARM_ATTRIBUTE_DESCRIPTOR   | "high_temperature_alarm"
    }

    // The Fleet picker offers a unit-less number only when it stores data points, so the counts
    // must keep STORE_DATA_POINTS; a count has no OpenRemote unit.
    def "count '#descriptor.name' stores data points and carries no units"() {
        expect:
        descriptor.getMeta().has(MetaItemType.STORE_DATA_POINTS)
        descriptor.getMeta().has(MetaItemType.READ_ONLY)
        descriptor.getMeta().has(MetaItemType.ACCESS_RESTRICTED_READ)
        !descriptor.units

        where:
        descriptor << [
            PassageCounterAsset.PERIOD_IN_ATTRIBUTE_DESCRIPTOR,
            PassageCounterAsset.PERIOD_OUT_ATTRIBUTE_DESCRIPTOR,
            PassageCounterAsset.TOTAL_IN_ATTRIBUTE_DESCRIPTOR,
            PassageCounterAsset.TOTAL_OUT_ATTRIBUTE_DESCRIPTOR
        ]
    }

    def "temperature is in degrees Celsius and battery_level stores no data points"() {
        expect:
        PassageCounterAsset.TEMPERATURE_ATTRIBUTE_DESCRIPTOR.units == [Constants.UNITS_CELSIUS] as String[]
        PassageCounterAsset.TEMPERATURE_ATTRIBUTE_DESCRIPTOR.getMeta().has(MetaItemType.STORE_DATA_POINTS)
        PassageCounterAsset.BATTERY_LEVEL_ATTRIBUTE_DESCRIPTOR.units == [Constants.UNITS_PERCENTAGE] as String[]
        !PassageCounterAsset.BATTERY_LEVEL_ATTRIBUTE_DESCRIPTOR.getMeta().has(MetaItemType.STORE_DATA_POINTS)
    }

    def "declares no delivery_point_id and every measurement key is snake_case"() {
        given:
        def names = PassageCounterAsset.declaredFields
            .findAll { it.name.endsWith("_ATTRIBUTE_DESCRIPTOR") }
            .collect { it.get(null).name }

        expect:
        // A shop entrance has no EAN/EIC.
        !names.contains("delivery_point_id")
        names.contains("period_in")
        // devEui and rawValue are the ingest-contract names; all the rest are snake_case.
        names.findAll { !(it in ["devEui", "rawValue"]) }.every { it ==~ /^[a-z0-9]+(_[a-z0-9]+)*$/ }
    }

    def "meter_id and external_id are readable by restricted users"() {
        expect:
        [PassageCounterAsset.METER_ID_ATTRIBUTE_DESCRIPTOR,
         PassageCounterAsset.EXTERNAL_ID_ATTRIBUTE_DESCRIPTOR].every {
            it.getMeta().has(MetaItemType.ACCESS_RESTRICTED_READ)
        }
    }
}
