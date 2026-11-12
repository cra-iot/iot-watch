package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.FleetColumnResolver
import org.openremote.manager.iotwatch.FleetExportAttributes
import org.openremote.model.util.ValueUtil
import spock.lang.Specification

class FleetExportAttributesTest extends Specification {

    def setupSpec() {
        ValueUtil.initialise(null)
    }

    static List<String> names(List descriptors) {
        return descriptors.collect { it.name }
    }

    def "water meter measurements"() {
        when:
        def names = names(FleetExportAttributes.measurements("WaterMeterAsset", false))

        then:
        names.contains("currentReading")
        !names.contains("battery_level")
        !names.contains("rawValue")
        !names.contains("devEui")
        !names.contains("location")
    }

    def "water meter identity"() {
        when:
        def all = names(FleetExportAttributes.identity("WaterMeterAsset", false))
        def restricted = names(FleetExportAttributes.identity("WaterMeterAsset", true))

        then:
        all.take(3) == ["devEui", "meter_id", "external_id"]
        all.contains("notes")
        all.contains("tags")
        restricted.contains("devEui")
        ["notes", "model", "manufacturer", "email", "tags"].every { !restricted.contains(it) }
    }

    def "electric meter offers per-phase power"() {
        when:
        def names = names(FleetExportAttributes.measurements("ElectricMeterAsset", false))

        then:
        names.containsAll(["power_1", "power_2", "energy_active_total"])
    }

    def "types without history are not exportable"() {
        expect:
        FleetExportAttributes.exportable(type) == expected

        where:
        type                 | expected
        "TrackerAsset"       | false
        "ShipTrackerAsset"   | false
        "RfidChipAsset"      | false
        "WaterMeterAsset"    | true
        "GasMeterAsset"      | true
        "HeatMeterAsset"     | true
        "ElectricMeterAsset" | true
    }

    def "unknown type yields empty lists"() {
        expect:
        FleetExportAttributes.identity("NoSuchAsset", false).isEmpty()
        FleetExportAttributes.measurements("NoSuchAsset", false).isEmpty()
        !FleetExportAttributes.exportable("NoSuchAsset")
    }

    def "no non-scalar measurement anywhere"() {
        expect:
        FleetColumnResolver.deviceTypes().every { type ->
            FleetExportAttributes.measurements(type, false).every {
                def c = it.type.type
                !c.isArray() && (Number.isAssignableFrom(c) || String == c || Boolean == c)
            }
        }
    }
}
