package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.FleetExportRegisters
import org.openremote.model.util.ValueUtil
import spock.lang.Specification

class FleetExportRegistersTest extends Specification {

    def setupSpec() {
        ValueUtil.initialise(null)
    }

    def "electricity has total, t1 and t2 in kWh"() {
        when:
        def groups = FleetExportRegisters.groups("ElectricMeterAsset", false)

        then:
        groups*.key() == ["total", "t1", "t2"]
        groups*.attribute() == ["energy_active_total", "energy_active_t1", "energy_active_t2"]
        groups.every { it.unit() == "kWh" }
    }

    def "water volume is m3"() {
        when:
        def groups = FleetExportRegisters.groups("WaterMeterAsset", false)

        then:
        groups.size() == 1
        groups[0].key() == "volume"
        groups[0].attribute() == "currentReading"
        groups[0].unit() == "m3"
        !groups[0].label().isEmpty()
    }

    def "passage counter has no unit suffix"() {
        expect:
        FleetExportRegisters.header(FleetExportRegisters.groups("PassageCounterAsset", false)[0], "start") == "in_start"
    }

    def "header carries the unit"() {
        expect:
        FleetExportRegisters.header(FleetExportRegisters.groups("ElectricMeterAsset", false)[1], "consumption") == "t1_consumption_kWh"
    }

    def "trackers are not exportable"() {
        expect:
        !FleetExportRegisters.exportable("TrackerAsset")
        FleetExportRegisters.exportable("GasMeterAsset")
        FleetExportRegisters.groups("Nope", false).isEmpty()
    }

    def "heat energy unit follows its descriptor"() {
        when:
        def group = FleetExportRegisters.groups("HeatMeterAsset", false)[0]
        def descriptor = ValueUtil.getAssetInfo("HeatMeterAsset").get().attributeDescriptors.get("energy_heating")

        then:
        group.unit() == FleetExportRegisters.unit(descriptor)
        !group.unit().isEmpty()
    }
}
