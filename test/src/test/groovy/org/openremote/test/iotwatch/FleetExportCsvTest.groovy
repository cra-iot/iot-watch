package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.FleetExportCsv
import org.openremote.manager.iotwatch.FleetExportPeriod
import org.openremote.manager.iotwatch.FleetExportRegisters
import org.openremote.model.iotwatch.FleetExportReport
import org.openremote.model.util.ValueUtil
import spock.lang.Specification

import java.time.Instant

class FleetExportCsvTest extends Specification {

    static final Instant NOW = Instant.parse("2026-11-12T09:30:00Z")

    def setupSpec() {
        ValueUtil.initialise(null)
    }

    def "quotes commas, quotes and line breaks"() {
        expect:
        FleetExportCsv.line(["a,b", 'say "hi"', "x\ny", "Příkon", null]) == '"a,b","say ""hi""","x\ny",Příkon,\r\n'
    }

    def "neutralizes text cells a spreadsheet would run as formulas"() {
        expect:
        FleetExportCsv.line(["=1+2", "+cmd", "-1+cmd|x", "@SUM(A1)", "\tx", "-5", "+5", "-0.25", "a=b", ""]) ==
                "'=1+2,'+cmd,'-1+cmd|x,'@SUM(A1),'\tx,-5,+5,-0.25,a=b,\r\n"
        FleetExportCsv.line(["=a,b"]) == '"\'=a,b"\r\n'
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

    def "header follows period and groups"() {
        given:
        def period = FleetExportPeriod.parse(FleetExportReport.DAILY, "2026-10-01", "2026-10-31", NOW)
        def groups = FleetExportRegisters.groups("ElectricMeterAsset", false)

        expect:
        FleetExportCsv.header(period, groups) == ["date", "assetId", "name", "meter_id", "devEui", "external_id",
                                                  "delivery_point_id", "end_reading_at",
                                                  "total_start_kWh", "total_end_kWh", "total_consumption_kWh",
                                                  "t1_start_kWh", "t1_end_kWh", "t1_consumption_kWh",
                                                  "t2_start_kWh", "t2_end_kWh", "t2_consumption_kWh"]
        FleetExportCsv.header(period, []).join(",").startsWith("date,assetId")
    }

    def "names files by type, report and period"() {
        expect:
        FleetExportCsv.fileName("WaterMeterAsset",
                FleetExportPeriod.parse(FleetExportReport.MONTHLY, "2025-11-01", "2026-10-01", NOW), NOW) ==
                "WaterMeterAsset_monthly_2025-11_2026-10_20261112T0930Z.csv"
        FleetExportCsv.fileName("GasMeterAsset",
                FleetExportPeriod.parse(FleetExportReport.DAILY, "2026-10-01", "2026-10-31", NOW), NOW) ==
                "GasMeterAsset_daily_2026-10-01_2026-10-31_20261112T0930Z.csv"
    }

    def "BOM bytes"() {
        expect:
        FleetExportCsv.UTF8_BOM == [0xEF, 0xBB, 0xBF] as byte[]
    }
}
