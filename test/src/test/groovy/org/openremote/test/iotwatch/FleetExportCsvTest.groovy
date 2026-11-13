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

    def "identifiers are written as Excel text so long numbers keep every digit"() {
        expect:
        FleetExportCsv.text("859182400524640589") == '="859182400524640589"'
        FleetExportCsv.text("") == ""
        FleetExportCsv.text(null) == null
        FleetExportCsv.line([FleetExportCsv.text("859182400524640589"), FleetExportCsv.text('a"b')]) ==
                '"=""859182400524640589""","=""a""""b"""\r\n'
    }

    def "only a lone text literal escapes formula neutralisation"() {
        expect:
        FleetExportCsv.line(['="x"&HYPERLINK("y")', "=1+2", '="x"']) ==
                '"\'=""x""&HYPERLINK(""y"")",\'=1+2,"=""x"""\r\n'
    }

    def "neutralizes formulas after a semicolon, which some Excel locales split cells on"() {
        expect:
        FleetExportCsv.line(["x;=1+1;", "a;+b;-c;@d;\te", 'a;"=1"', "a; =1", "a;b"]) ==
                "x;'=1+1;,a;'+b;'-c;'@d;'\te,\"a;'\"\"=1\"\"\",a; =1,a;b\r\n"
        FleetExportCsv.line([FleetExportCsv.text("x;=1+1;")]) == "\"=\"\"x;'=1+1;\"\"\"\r\n"
        FleetExportCsv.render(["a", "-5"]) == "a; -5"
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
