package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.FleetColumnResolver
import org.openremote.model.attribute.MetaItem
import org.openremote.model.iotwatch.FleetColumns
import org.openremote.model.iotwatch.FleetQuery
import org.openremote.model.util.ValueUtil
import org.openremote.model.value.AttributeDescriptor
import org.openremote.model.value.MetaItemType
import org.openremote.model.value.ValueType
import spock.lang.Specification

class FleetColumnResolverTest extends Specification {

    def setupSpec() {
        ValueUtil.initialise(null)
    }

    static FleetColumnResolver resolver(Map<String, String> overrides = [:]) {
        return new FleetColumnResolver(overrides)
    }

    def "a declared type resolves to its declared register"() {
        expect:
        resolver().primaryRegister(type) == register

        where:
        type                  | register
        "WaterMeterAsset"     | "currentReading"
        "GasMeterAsset"       | "volume"
        "ElectricMeterAsset"  | "energy_active_total"
        "HeatMeterAsset"      | "energy_heating"
        "PassageCounterAsset"  | "period_in"
    }

    def "a configuration override beats the declaration"() {
        expect:
        resolver(["GasMeterAsset": "energy"]).primaryRegister("GasMeterAsset") == "energy"
    }

    def "an undeclared type falls back to its first measurement descriptor"() {
        given: "the descriptors of a type nobody declared - a temperature sensor"
        def descriptors = [
                new AttributeDescriptor<>("devEui", ValueType.TEXT),
                new AttributeDescriptor<>("temperature", ValueType.NUMBER,
                        new MetaItem<>(MetaItemType.LABEL, "Teplota"),
                        new MetaItem<>(MetaItemType.STORE_DATA_POINTS))
                        .withUnits(org.openremote.model.Constants.UNITS_CELSIUS),
                new AttributeDescriptor<>("humidity", ValueType.NUMBER,
                        new MetaItem<>(MetaItemType.STORE_DATA_POINTS))
                        .withUnits(org.openremote.model.Constants.UNITS_PERCENTAGE),
        ]

        expect: "the identity attribute is skipped and the first measurement wins"
        FleetColumnResolver.firstMeasurement(descriptors) == "temperature"
    }

    def "a type with no measurement descriptor resolves to no primary register"() {
        given:
        def descriptors = [
                new AttributeDescriptor<>("devEui", ValueType.TEXT),
                new AttributeDescriptor<>("tamper", ValueType.BOOLEAN),
        ]

        expect:
        FleetColumnResolver.firstMeasurement(descriptors) == null
    }

    def "battery_level is never the primary register"() {
        given: "battery_level carries units but deliberately no STORE_DATA_POINTS"
        def descriptors = [
                new AttributeDescriptor<>("battery_level", ValueType.POSITIVE_INTEGER)
                        .withUnits(org.openremote.model.Constants.UNITS_PERCENTAGE),
                new AttributeDescriptor<>("volume", ValueType.NUMBER,
                        new MetaItem<>(MetaItemType.STORE_DATA_POINTS))
                        .withUnits(org.openremote.model.Constants.UNITS_METRE),
        ]

        expect:
        FleetColumnResolver.firstMeasurement(descriptors) == "volume"
    }

    def "the select carries the infrastructure attributes whatever the type"() {
        when:
        def select = resolver().selectAttributes(new FleetQuery(), ["GasMeterAsset"]) as Set

        then:
        select.containsAll(FleetColumns.INFRASTRUCTURE_ATTRIBUTES as List)
    }

    def "the select carries every alarm boolean the type declares"() {
        when: "a gas meter is queried"
        def select = resolver().selectAttributes(new FleetQuery(), ["GasMeterAsset"]) as Set

        then: "its alarm booleans are fetched, or FleetRowMapper finds no alarms to report"
        select.contains("low_battery")
        select.contains("tamper")

        and: "a state boolean is not an alarm and is not fetched as one"
        !select.contains("valve_closed")
    }

    def "rawValue is never selected, even when a caller asks for it as a column"() {
        given:
        def query = new FleetQuery()
        query.columns = ["rawValue"]

        when:
        def select = resolver().selectAttributes(query, ["GasMeterAsset"]) as Set

        then:
        !select.contains("rawValue")
    }

    def "no types in the result means no columns, not every registered type's columns"() {
        expect:
        resolver().columnsFor([], null).isEmpty()
    }

    def "the select carries the primary register and any column the caller asked for"() {
        given:
        def query = new FleetQuery()
        query.columns = ["gas_temperature"]

        when:
        def select = resolver().selectAttributes(query, ["GasMeterAsset"]) as Set

        then:
        select.contains("volume")
        select.contains("gas_temperature")
    }

    def "an empty type filter resolves over every registered type"() {
        when: "the whole realm is queried, so the types are not known up front"
        def select = resolver().selectAttributes(new FleetQuery(), []) as Set

        then: "every declared type's primary register is fetched"
        select.contains("volume")
        select.contains("currentReading")
        select.contains("energy_active_total")
    }

    def "columns mark the primary and offer the rest"() {
        when:
        def columns = resolver().columnsFor(["GasMeterAsset"], null)

        then: "the primary register is selected by default"
        def primary = columns.find { it.primary }
        primary.name == "volume"
        primary.selected
        primary.units == ["metre", "cubed"] as String[]
        primary.label == "Objem plynu"

        and: "the other registers are offered but not selected, so the picker has something to show"
        columns.any { !it.selected && it.name == "volume_standard" }
    }

    def "a requested column comes back selected"() {
        when:
        def columns = resolver().columnsFor(["GasMeterAsset"], ["gas_temperature"])

        then:
        columns.find { it.name == "gas_temperature" }.selected
    }

    def "a type whose numbers are not stored as datapoints still offers picker columns, none primary"() {
        when: "a tracker: its battery has units but no STORE_DATA_POINTS"
        def columns = resolver().columnsFor(["TrackerAsset"], null)

        then: "it is offered for the picker"
        columns.any { it.assetType == "TrackerAsset" && it.name == "battery" }

        and: "but the strict measurement rule still decides the primary register"
        columns.every { !it.primary && !it.selected }
        resolver().primaryRegister("TrackerAsset") == null
    }

    def "people counters offer their unit-less counts in the picker"() {
        when:
        def columns = resolver().columnsFor(["PassageCounterAsset"], null)
        def byName = columns.collectEntries { [it.name, it] }

        then: "period_in is the reading, shown without units"
        byName["period_in"].primary
        byName["period_in"].selected
        byName["period_in"].units == null
        byName["period_in"].label == "Příchody za interval"

        and: "the other counts and the temperature are offered but not selected"
        ["period_out", "total_in", "total_out", "temperature"].every {
            byName.containsKey(it) && !byName[it].selected && !byName[it].primary
        }

        and: "infrastructure attributes and rawValue stay out"
        !byName.containsKey("battery_level")
        !byName.containsKey("rawValue")
    }

    def "a unit-less number is offered only when it stores data points"() {
        given:
        def stored = new AttributeDescriptor<>("visits", ValueType.POSITIVE_INTEGER,
                new MetaItem<>(MetaItemType.STORE_DATA_POINTS))
        def notStored = new AttributeDescriptor<>("setting", ValueType.POSITIVE_INTEGER)

        expect:
        FleetColumnResolver.isOffered(stored)
        !FleetColumnResolver.isOffered(notStored)

        and: "an existing type gains its unit-less stored numbers as columns: the electric meter tariff"
        resolver().columnsFor(["ElectricMeterAsset"], null).any { it.name == "tariff" }
    }

    def "infrastructure attributes and rawValue are never offered as picker columns"() {
        when:
        def names = resolver().columnsFor(["GasMeterAsset", "WaterMeterAsset", "TrackerAsset", "PassageCounterAsset"], null)*.name

        then:
        !names.contains("battery_level")
        !names.contains("rawValue")
    }

    def "an empty type filter stands for exactly the types that declare rawValue"() {
        when:
        def types = FleetColumnResolver.deviceTypes()

        then: "every device type the ingest writes to"
        types.containsAll(["WaterMeterAsset", "GasMeterAsset", "ElectricMeterAsset", "HeatMeterAsset", "TrackerAsset",
                           "PassageCounterAsset"])

        and: "resolved from the model: each one declares rawValue, and no other type is included"
        types == ValueUtil.getAssetInfos(null)
                .findAll { !it.isAgent() && it.attributeDescriptors.containsKey(FleetColumns.RAW_VALUE) }
                .collect { it.assetDescriptor.name }
                .sort()

        and: "structural assets are not devices"
        !types.contains("ThingAsset")
        !types.contains("BuildingAsset")
    }

    def "the empty-filter select resolves over the device types only"() {
        when:
        def select = resolver().selectAttributes(new FleetQuery(), null) as Set

        then: "a device type's alarm is fetched"
        select.contains("leakage")
    }
}
