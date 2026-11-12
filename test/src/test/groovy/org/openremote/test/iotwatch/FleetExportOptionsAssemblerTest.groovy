package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.FleetColumnResolver
import org.openremote.manager.iotwatch.FleetExportOptionsAssembler
import org.openremote.model.util.ValueUtil
import spock.lang.Specification

import java.time.LocalDate

class FleetExportOptionsAssemblerTest extends Specification {

    static final LocalDate MAX_TO = LocalDate.of(2026, 11, 11)

    def setupSpec() {
        ValueUtil.initialise(null)
    }

    static def assemble(Map<String, Integer> counts, boolean restricted = false) {
        return FleetExportOptionsAssembler.assemble(counts, restricted, new FleetColumnResolver([:]), MAX_TO)
    }

    def "lists only exportable types, sorted"() {
        when:
        def options = assemble([WaterMeterAsset: 3, TrackerAsset: 2, ElectricMeterAsset: 1])

        then:
        options.types*.type == ["ElectricMeterAsset", "WaterMeterAsset"]
        options.types.find { it.type == "WaterMeterAsset" }.deviceCount == 3
        options.types.find { it.type == "ElectricMeterAsset" }.deviceCount == 1
    }

    def "defaults to identifiers and the primary register"() {
        when:
        def water = assemble([WaterMeterAsset: 3]).types[0]
        def defaults = (water.identity + water.measurements).findAll { it.defaultSelected }*.name

        then:
        defaults == ["meter_id", "devEui", "external_id", "delivery_point_id", "currentReading"]
        !water.identity*.name.contains("notes")
        water.groups*.key == ["volume"]
        (water.identity + water.measurements).every { it.label != null }
    }

    def "restricted callers see no generic attributes"() {
        when:
        def water = assemble([WaterMeterAsset: 3], true).types[0]

        then:
        water.identity*.name.contains("devEui")
        !water.identity*.name.contains("notes")
    }

    def "carries maxTo"() {
        when:
        def options = assemble([:])

        then:
        options.maxTo == "2026-11-11"
        options.maxDays == 366
        options.types.isEmpty()
    }
}
