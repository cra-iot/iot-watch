package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.FleetExportOptionsAssembler
import org.openremote.model.util.ValueUtil
import spock.lang.Specification

import java.time.Instant

class FleetExportOptionsAssemblerTest extends Specification {

    static final Instant NOW = Instant.parse("2026-11-12T09:30:00Z")

    def setupSpec() {
        ValueUtil.initialise(null)
    }

    static def assemble(Map<String, Integer> counts, boolean restricted = false) {
        return FleetExportOptionsAssembler.assemble(counts, restricted, NOW)
    }

    def "offers only types with readings, with their groups"() {
        when:
        def options = assemble([WaterMeterAsset: 3, TrackerAsset: 2, ElectricMeterAsset: 1])

        then:
        options.types*.type == ["ElectricMeterAsset", "WaterMeterAsset"]
        options.types.find { it.type == "WaterMeterAsset" }.deviceCount == 3
        options.types.find { it.type == "ElectricMeterAsset" }.deviceCount == 1
        options.types.find { it.type == "ElectricMeterAsset" }.groups*.key == ["total", "t1", "t2"]
        options.types.find { it.type == "ElectricMeterAsset" }.groups*.attribute ==
                ["energy_active_total", "energy_active_t1", "energy_active_t2"]
        options.lastDay == "2026-11-11"
        options.lastMonth == "2026-10"
        options.maxDays == 366
        options.maxMonths == 24
    }

    def "a group carries its label and unit"() {
        when:
        def water = assemble([WaterMeterAsset: 3]).types[0]

        then:
        water.groups*.key == ["volume"]
        water.groups*.attribute == ["currentReading"]
        water.groups.every { it.label != null && !it.label.isEmpty() }
        water.groups[0].unit == "m3"
    }

    def "restricted callers are offered the readable groups"() {
        when:
        def options = assemble([WaterMeterAsset: 3], true)

        then:
        options.types*.type == ["WaterMeterAsset"]
        options.types[0].groups*.key == ["volume"]
    }

    def "without matched devices no type is offered"() {
        when:
        def options = assemble([:])

        then:
        options.types.isEmpty()
        options.lastDay == "2026-11-11"
        options.lastMonth == "2026-10"
    }
}
