package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.FleetColumnResolver
import org.openremote.manager.iotwatch.FleetRowMapper
import org.openremote.model.attribute.Attribute
import org.openremote.model.electricmeter.ElectricMeterAsset
import org.openremote.model.gasmeter.GasMeterAsset
import org.openremote.model.tracker.TrackerAsset
import org.openremote.model.util.ValueUtil
import org.openremote.model.value.ValueType
import org.openremote.model.watermeter.WaterMeterAsset
import spock.lang.Specification

class FleetRowMapperTest extends Specification {

    /** The real resolver, with no configuration override - the mapper holds no table of its own. */
    static final FleetColumnResolver RESOLVER = new FleetColumnResolver([:])

    static final long T = 1_700_000_000_000L

    def setupSpec() {
        ValueUtil.initialise(null)
    }

    static WaterMeterAsset water(String name) {
        def asset = new WaterMeterAsset(name)
        asset.setId("0123456789012345678901")
        return asset
    }

    def "the primary register is the type's own register, with its timestamp as lastReading"() {
        given:
        def asset = water("Byt 12")
        asset.getAttributes().addOrReplace(
                new Attribute<>(WaterMeterAsset.CURRENT_READING_ATTRIBUTE_DESCRIPTOR, 142.318d, T))

        when:
        def row = FleetRowMapper.map(asset, RESOLVER, ["currentReading"], null)

        then:
        row.primaryName == "currentReading"
        row.values.get("currentReading") == 142.318d
        row.lastReading == T
    }

    def "the gas primary register is volume"() {
        given:
        def asset = new GasMeterAsset("Kotelna")
        asset.setId("0123456789012345678902")
        asset.getAttributes().addOrReplace(
                new Attribute<>(GasMeterAsset.VOLUME_ATTRIBUTE_DESCRIPTOR, 8214d, T))

        when:
        def row = FleetRowMapper.map(asset, RESOLVER, ["volume"], null)

        then:
        row.primaryName == "volume"
        row.values.get("volume") == 8214d
    }

    def "every requested column lands in the value map, not just the primary one"() {
        given: "an electric meter queried with both tariff registers"
        def asset = new ElectricMeterAsset("Hlavni")
        asset.setId("0123456789012345678904")
        asset.getAttributes().addOrReplace(
                new Attribute<>(ElectricMeterAsset.ENERGY_ACTIVE_TOTAL_ATTRIBUTE_DESCRIPTOR, 142905d, T))
        asset.getAttributes().addOrReplace(
                new Attribute<>(ElectricMeterAsset.ENERGY_ACTIVE_T1_ATTRIBUTE_DESCRIPTOR, 98410d, T))

        when:
        def row = FleetRowMapper.map(asset, RESOLVER,
                ["energy_active_total", "energy_active_t1", "energy_active_t2"], null)

        then:
        row.primaryName == "energy_active_total"
        row.values.get("energy_active_total") == 142905d
        row.values.get("energy_active_t1") == 98410d

        and: "a requested column the asset does not carry is absent, not null-valued"
        !row.values.containsKey("energy_active_t2")
    }

    def "lastSeen is the timestamp handed in, and a loaded rawValue is never exposed"() {
        given: "a rawValue that should not have been loaded, but is - defence in depth"
        def asset = water("Byt 12")
        def raw = new Attribute<>(WaterMeterAsset.RAW_VALUE_ATTRIBUTE_DESCRIPTOR,
                ValueUtil.JSON.convertValue([secret: "device payload"], ValueType.ObjectMap.class), T - 1)
        asset.getAttributes().addOrReplace(raw)

        when: "the lookup in FleetResourceImpl supplies the timestamp"
        def row = FleetRowMapper.map(asset, RESOLVER, ["currentReading", "rawValue"], T)
        def json = ValueUtil.JSON.writeValueAsString(row)

        then:
        row.lastSeen == T
        !json.contains("device payload")
        !json.contains("rawValue")
    }

    def "an asset that never reported has null timestamps and no primary value"() {
        given: "the register exists but has never received a value"
        def asset = water("Nove")
        asset.getAttributes().addOrReplace(
                new Attribute<>(WaterMeterAsset.CURRENT_READING_ATTRIBUTE_DESCRIPTOR))

        when:
        def row = FleetRowMapper.map(asset, RESOLVER, ["currentReading"], null)

        then:
        row.lastSeen == null
        row.lastReading == null
        row.primaryName == "currentReading"
        row.values.get("currentReading") == null
    }

    def "a type with no resolvable register yields a null primary name, not an exception"() {
        given:
        def asset = new org.openremote.model.custom.CustomAsset()
        asset.setId("0123456789012345678903")
        asset.setName("Something")

        when:
        def row = FleetRowMapper.map(asset, RESOLVER, [], null)

        then:
        row.primaryName == null
        row.values.isEmpty()
    }

    def "only boolean attributes that are currently true are reported as alarms"() {
        given:
        def asset = water("Byt 12")
        asset.getAttributes().addOrReplace(
                new Attribute<>(WaterMeterAsset.LEAKAGE_ATTRIBUTE_DESCRIPTOR, true, T))
        asset.getAttributes().addOrReplace(
                new Attribute<>(WaterMeterAsset.BURST_ATTRIBUTE_DESCRIPTOR, false, T))

        when:
        def row = FleetRowMapper.map(asset, RESOLVER, ["currentReading"], null)

        then:
        row.alarms == ["leakage"]
    }

    def "a true state boolean is not reported as an alarm"() {
        given: "a gas meter whose shut-off valve is closed"
        def asset = new GasMeterAsset("Kotelna")
        asset.setId("0123456789012345678905")
        asset.getAttributes().addOrReplace(
                new Attribute<>(GasMeterAsset.VALVE_CLOSED_ATTRIBUTE_DESCRIPTOR, true, T))

        when:
        def row = FleetRowMapper.map(asset, RESOLVER, ["volume"], null)

        then:
        row.alarms.isEmpty()
    }

    def "identity attributes are copied onto the row"() {
        given:
        def asset = water("Byt 12")
        asset.getAttributes().addOrReplace(
                new Attribute<>(WaterMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR, "70B3D5", T))
        asset.getAttributes().addOrReplace(
                new Attribute<>(WaterMeterAsset.METER_ID_ATTRIBUTE_DESCRIPTOR, "21345118", T))

        when:
        def row = FleetRowMapper.map(asset, RESOLVER, ["currentReading"], null)

        then:
        row.devEui == "70B3D5"
        row.meterId == "21345118"
        row.deliveryPointId == null
    }

    def "a tracker in use is not in alarm"() {
        given: "active is the tracker's assignment state, true whenever it is in use"
        def asset = new TrackerAsset("Tracker")
        asset.setId("0123456789012345678906")
        asset.getAttributes().addOrReplace(
                new Attribute<>(TrackerAsset.ACTIVE_ATTRIBUTE_DESCRIPTOR, true, T))

        when:
        def row = FleetRowMapper.map(asset, RESOLVER, [], null)

        then:
        row.alarms.isEmpty()
    }
}
