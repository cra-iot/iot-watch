package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.FleetExportCsv
import org.openremote.manager.iotwatch.FleetExportRange
import spock.lang.Specification

import java.time.Instant

class FleetExportCsvTest extends Specification {

    static final Instant NOW = Instant.parse("2026-11-12T09:30:00Z")

    def "quotes commas, quotes and line breaks"() {
        expect:
        FleetExportCsv.line(["a,b", 'say "hi"', "x\ny", "Příkon", null]) == '"a,b","say ""hi""","x\ny",Příkon,\r\n'
    }

    def "renders values"() {
        expect:
        FleetExportCsv.render(12.0d) == "12"
        FleetExportCsv.render(1.0E7d) == "10000000"
        FleetExportCsv.render(0.125d) == "0.125"
        FleetExportCsv.render(true) == "true"
        FleetExportCsv.render(["a", "b"] as String[]) == "a; b"
        FleetExportCsv.render(["a", "b"]) == "a; b"
        FleetExportCsv.render(null) == ""
        FleetExportCsv.render("text") == "text"
    }

    def "formats instants"() {
        expect:
        FleetExportCsv.instant(Instant.parse("2026-10-28T14:30:00.123Z")) == "2026-10-28T14:30:00Z"
        FleetExportCsv.instant(null) == ""
    }

    def "names files"() {
        given:
        def utc = FleetExportRange.parse("2026-10-01", "2026-10-31", null, NOW)
        def prague = FleetExportRange.parse("2026-10-01", "2026-10-31", "Europe/Prague", NOW)

        expect:
        FleetExportCsv.csvName("WaterMeterAsset", utc, NOW) == "WaterMeterAsset_2026-10-01_2026-10-31_20261112T0930Z.csv"
        FleetExportCsv.zipName(utc, NOW) == "fleet-export_2026-10-01_2026-10-31_20261112T0930Z.zip"
        FleetExportCsv.csvName("WaterMeterAsset", prague, NOW).endsWith("_Europe-Prague.csv")
        FleetExportCsv.zipName(prague, NOW).endsWith("_Europe-Prague.zip")
    }

    def "BOM bytes"() {
        expect:
        FleetExportCsv.UTF8_BOM == [0xEF, 0xBB, 0xBF] as byte[]
    }
}
