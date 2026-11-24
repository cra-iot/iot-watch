package org.openremote.test.iotwatch

import org.openremote.model.Constants
import org.openremote.model.soundlevelmeter.SoundLevelMeterAsset
import org.openremote.model.value.MetaItemType
import org.openremote.model.value.ValueType
import spock.lang.Specification

class SoundLevelMeterDescriptorTest extends Specification {

    def "sound level meter requires devEui and rawValue; identity + measurements optional"() {
        expect:
        !SoundLevelMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR.isOptional()
        SoundLevelMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR.type == ValueType.TEXT
        !SoundLevelMeterAsset.RAW_VALUE_ATTRIBUTE_DESCRIPTOR.isOptional()
        SoundLevelMeterAsset.RAW_VALUE_ATTRIBUTE_DESCRIPTOR.type == ValueType.JSON_OBJECT
        SoundLevelMeterAsset.METER_ID_ATTRIBUTE_DESCRIPTOR.isOptional()
        SoundLevelMeterAsset.METER_ID_ATTRIBUTE_DESCRIPTOR.name == "meter_id"
        SoundLevelMeterAsset.METER_ID_ATTRIBUTE_DESCRIPTOR.type == ValueType.TEXT
        SoundLevelMeterAsset.EXTERNAL_ID_ATTRIBUTE_DESCRIPTOR.isOptional()
        SoundLevelMeterAsset.EXTERNAL_ID_ATTRIBUTE_DESCRIPTOR.name == "external_id"
        SoundLevelMeterAsset.EXTERNAL_ID_ATTRIBUTE_DESCRIPTOR.type == ValueType.TEXT
    }

    def "level '#descriptor.name' is an optional number in decibels that stores data points"() {
        expect:
        descriptor.isOptional()
        descriptor.name == key
        descriptor.type == ValueType.NUMBER
        descriptor.units == [Constants.UNITS_DECIBEL] as String[]
        descriptor.getMeta().has(MetaItemType.STORE_DATA_POINTS)
        descriptor.getMeta().has(MetaItemType.RULE_STATE)
        descriptor.getMeta().has(MetaItemType.READ_ONLY)
        descriptor.getMeta().has(MetaItemType.ACCESS_RESTRICTED_READ)

        where:
        descriptor                                                  | key
        SoundLevelMeterAsset.SOUND_LEVEL_ATTRIBUTE_DESCRIPTOR       | "sound_level"
        SoundLevelMeterAsset.SOUND_LEVEL_EQ_ATTRIBUTE_DESCRIPTOR    | "sound_level_eq"
        SoundLevelMeterAsset.SOUND_LEVEL_MAX_ATTRIBUTE_DESCRIPTOR   | "sound_level_max"
    }

    def "weighting '#descriptor.name' is optional text with history and no units"() {
        expect:
        descriptor.isOptional()
        descriptor.name == key
        descriptor.type == ValueType.TEXT
        !descriptor.units
        descriptor.getMeta().has(MetaItemType.STORE_DATA_POINTS)
        descriptor.getMeta().has(MetaItemType.RULE_STATE)
        descriptor.getMeta().has(MetaItemType.READ_ONLY)
        descriptor.getMeta().has(MetaItemType.ACCESS_RESTRICTED_READ)

        where:
        descriptor                                                        | key
        SoundLevelMeterAsset.FREQUENCY_WEIGHTING_ATTRIBUTE_DESCRIPTOR     | "frequency_weighting"
        SoundLevelMeterAsset.TIME_WEIGHTING_ATTRIBUTE_DESCRIPTOR          | "time_weighting"
    }

    def "battery_level is a percentage without data points"() {
        expect:
        SoundLevelMeterAsset.BATTERY_LEVEL_ATTRIBUTE_DESCRIPTOR.isOptional()
        SoundLevelMeterAsset.BATTERY_LEVEL_ATTRIBUTE_DESCRIPTOR.name == "battery_level"
        SoundLevelMeterAsset.BATTERY_LEVEL_ATTRIBUTE_DESCRIPTOR.units == [Constants.UNITS_PERCENTAGE] as String[]
        !SoundLevelMeterAsset.BATTERY_LEVEL_ATTRIBUTE_DESCRIPTOR.getMeta().has(MetaItemType.STORE_DATA_POINTS)
        !SoundLevelMeterAsset.BATTERY_LEVEL_ATTRIBUTE_DESCRIPTOR.getMeta().has(MetaItemType.RULE_STATE)
    }

    def "declares no delivery_point_id, no booleans and every key is snake_case"() {
        given:
        def descriptors = SoundLevelMeterAsset.declaredFields
            .findAll { it.name.endsWith("_ATTRIBUTE_DESCRIPTOR") }
            .collect { it.get(null) }
        def names = descriptors.collect { it.name }

        expect:
        !names.contains("delivery_point_id")
        !descriptors.any { it.type == ValueType.BOOLEAN }
        // devEui and rawValue are the ingest-contract names; all the rest are snake_case.
        names.findAll { !(it in ["devEui", "rawValue"]) }.every { it ==~ /^[a-z0-9]+(_[a-z0-9]+)*$/ }
        names.toSet() == ["devEui", "rawValue", "meter_id", "external_id", "sound_level", "sound_level_eq",
                          "sound_level_max", "frequency_weighting", "time_weighting", "battery_level"].toSet()
    }

    def "meter_id and external_id are readable by restricted users"() {
        expect:
        [SoundLevelMeterAsset.METER_ID_ATTRIBUTE_DESCRIPTOR,
         SoundLevelMeterAsset.EXTERNAL_ID_ATTRIBUTE_DESCRIPTOR].every {
            it.getMeta().has(MetaItemType.ACCESS_RESTRICTED_READ)
        }
    }

    def "asset descriptor uses volume-high in indigo"() {
        expect:
        SoundLevelMeterAsset.SOUND_LEVEL_METER_ASSET_DESCRIPTOR.icon == "volume-high"
        SoundLevelMeterAsset.SOUND_LEVEL_METER_ASSET_DESCRIPTOR.colour == "3949AB"
    }
}
