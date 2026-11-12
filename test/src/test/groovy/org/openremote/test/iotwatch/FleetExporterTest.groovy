package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.DailyRows
import org.openremote.manager.iotwatch.DailyValueRepository
import org.openremote.manager.iotwatch.FleetExportCsv
import org.openremote.manager.iotwatch.FleetExportRange
import org.openremote.manager.iotwatch.FleetExporter
import org.openremote.model.asset.Asset
import org.openremote.model.electricmeter.ElectricMeterAsset
import org.openremote.model.iotwatch.FleetExportRequest
import org.openremote.model.iotwatch.FleetQuery
import org.openremote.model.util.ValueUtil
import org.openremote.model.watermeter.WaterMeterAsset
import spock.lang.Specification

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipInputStream

class FleetExporterTest extends Specification {

    static final Instant NOW = Instant.parse("2026-11-12T09:30:00Z")
    static final String BOM = new String(FleetExportCsv.UTF8_BOM, StandardCharsets.UTF_8)

    def setupSpec() {
        ValueUtil.initialise(null)
    }

    /** Canned values for every requested asset and attribute; optionally fails for one attribute. */
    static class FakeRepository extends DailyValueRepository {
        String failOn

        FakeRepository() {
            super(null, 0)
        }

        @Override
        Map<String, Map<LocalDate, Map<String, DailyRows.Reading>>> daily(
                List<String> assetIds, List<String> attributes, FleetExportRange range) {
            if (failOn != null && attributes.contains(failOn)) {
                throw new IllegalStateException("database gone")
            }
            def result = [:]
            assetIds.each { id ->
                def perAttribute = attributes.collectEntries { [(it): new DailyRows.Reading("5", range.start().plusSeconds(3600))] }
                result[id] = [(range.from()): perAttribute]
            }
            return result
        }

        @Override
        Map<String, Map<String, DailyRows.Reading>> seed(
                List<String> assetIds, List<String> attributes, FleetExportRange range) {
            return [:]
        }
    }

    static WaterMeterAsset water(String id, String name) {
        def asset = new WaterMeterAsset(name)
        asset.setId(id)
        asset.getAttributes().getOrCreate(WaterMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR).setValue("TEST-EUI-" + id)
        asset.getAttributes().getOrCreate(WaterMeterAsset.CURRENT_READING_ATTRIBUTE_DESCRIPTOR)
        return asset
    }

    static ElectricMeterAsset electric(String id, String name) {
        def asset = new ElectricMeterAsset(name)
        asset.setId(id)
        asset.getAttributes().getOrCreate(ElectricMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR).setValue("TEST-EUI-" + id)
        asset.getAttributes().getOrCreate(ElectricMeterAsset.ENERGY_ACTIVE_TOTAL_ATTRIBUTE_DESCRIPTOR)
        return asset
    }

    static FleetExportRequest request(Map<String, List<String>> attributes, String from = "2026-10-01", String to = "2026-10-02") {
        def request = new FleetExportRequest()
        request.query = new FleetQuery()
        request.from = from
        request.to = to
        request.attributes = attributes
        return request
    }

    static List<String> lines(byte[] bytes) {
        def text = new String(bytes, StandardCharsets.UTF_8)
        assert text.startsWith(BOM)
        return text.substring(BOM.length()).split("\r\n").toList()
    }

    def exporter = new FleetExporter(new FakeRepository(), 1_000_000, 2)

    def "plan rejects"() {
        given:
        def exporter = new FleetExporter(new FakeRepository(), maxRows, 2)
        Map<String, List<Asset<?>>> assets = ["WaterMeterAsset": (1..3).collect { water("id" + it, "W" + it) }]

        when:
        exporter.plan(request(attributes, from, "2026-10-31"), assets, false, NOW)

        then:
        def e = thrown(IllegalArgumentException)
        e.message == message

        where:
        attributes                                    | from         | maxRows   | message
        [:]                                           | "2026-10-01" | 1_000_000 | "Select at least one attribute to export"
        ["ElectricMeterAsset": ["energy_active_total"]] | "2026-10-01" | 1_000_000 | "No matched devices of type ElectricMeterAsset"
        ["WaterMeterAsset": ["battery_level"]]        | "2026-10-01" | 1_000_000 | "Attribute battery_level cannot be exported for WaterMeterAsset"
        ["WaterMeterAsset": ["rawValue"]]             | "2026-10-01" | 1_000_000 | "Attribute rawValue cannot be exported for WaterMeterAsset"
        ["WaterMeterAsset": ["currentReading"]]       | "2025-10-31" | 1000      | "Export too large: narrow the search or the date range"
    }

    def "plan forbids generic attributes to restricted callers"() {
        when:
        exporter.plan(request(["WaterMeterAsset": ["devEui", "notes"]]),
                ["WaterMeterAsset": [water("id1", "A")]], true, NOW)

        then:
        thrown(SecurityException)
    }

    def "no matched devices gives a header-only csv"() {
        given:
        def plan = exporter.plan(request(["WaterMeterAsset": ["currentReading"]]), [:], false, NOW)
        def out = new ByteArrayOutputStream()

        when:
        def rows = exporter.write(plan, out)

        then:
        rows == 0
        new String(out.toByteArray(), StandardCharsets.UTF_8) == BOM + "date,assetId,name,lastReadingAt\r\n"
        exporter.fileName(plan).endsWith(".csv")
        exporter.contentType(plan) == "text/csv; charset=UTF-8"
    }

    def "writes one row per device per day, sorted by name then id"() {
        given:
        Map<String, List<Asset<?>>> assets = ["WaterMeterAsset": [water("id3", "B"), water("id2", "A"), water("id1", "A")]]
        // Request order deliberately differs from the column order
        def plan = exporter.plan(request(["WaterMeterAsset": ["currentReading", "devEui"]]), assets, false, NOW)
        def out = new ByteArrayOutputStream()

        when:
        def rows = exporter.write(plan, out)
        def lines = lines(out.toByteArray())

        then:
        rows == 6
        lines[0] == "date,assetId,name,lastReadingAt,devEui,currentReading"
        lines.drop(1).collect { it.split(",")[1] } == ["id1", "id1", "id2", "id2", "id3", "id3"]
        lines[1] == "2026-10-01,id1,A,2026-10-01T01:00:00Z,TEST-EUI-id1,5"
        lines[2] == "2026-10-02,id1,A,2026-10-01T01:00:00Z,TEST-EUI-id1,5"
        exporter.fileName(plan) == FleetExportCsv.csvName("WaterMeterAsset", plan.range(), plan.exportedAt())
        exporter.contentType(plan) == "text/csv; charset=UTF-8"
    }

    def "unreadable attribute on one asset leaves its cells empty"() {
        given:
        def hidden = water("id2", "B")
        hidden.getAttributes().remove("currentReading")
        Map<String, List<Asset<?>>> assets = ["WaterMeterAsset": [water("id1", "A"), hidden]]
        def plan = exporter.plan(request(["WaterMeterAsset": ["devEui", "currentReading"]]), assets, false, NOW)
        def out = new ByteArrayOutputStream()

        when:
        exporter.write(plan, out)
        def lines = lines(out.toByteArray())

        then:
        lines[1] == "2026-10-01,id1,A,2026-10-01T01:00:00Z,TEST-EUI-id1,5"
        lines[3] == "2026-10-01,id2,B,,TEST-EUI-id2,"
        lines[4] == "2026-10-02,id2,B,,TEST-EUI-id2,"
    }

    def "several types produce a zip with one csv each"() {
        given:
        Map<String, List<Asset<?>>> assets = [
                "WaterMeterAsset"   : [water("id1", "W")],
                "ElectricMeterAsset": [electric("id2", "E")]]
        def plan = exporter.plan(request([
                "WaterMeterAsset"   : ["currentReading"],
                "ElectricMeterAsset": ["energy_active_total"]]), assets, false, NOW)
        def out = new ByteArrayOutputStream()

        when:
        exporter.write(plan, out)
        def entries = [:]
        new ZipInputStream(new ByteArrayInputStream(out.toByteArray())).withCloseable { zip ->
            def entry
            while ((entry = zip.nextEntry) != null) {
                entries[entry.name] = zip.readAllBytes()
            }
        }

        then:
        exporter.contentType(plan) == "application/zip"
        exporter.fileName(plan) == FleetExportCsv.zipName(plan.range(), plan.exportedAt())
        entries.keySet() == [
                FleetExportCsv.csvName("ElectricMeterAsset", plan.range(), plan.exportedAt()),
                FleetExportCsv.csvName("WaterMeterAsset", plan.range(), plan.exportedAt())] as Set
        entries.values().every { lines(it).size() == 3 }
    }

    def "a failure while writing propagates and leaves the zip unfinished"() {
        given:
        def repository = new FakeRepository(failOn: "currentReading")
        def exporter = new FleetExporter(repository, 1_000_000, 2)
        Map<String, List<Asset<?>>> assets = [
                "WaterMeterAsset"   : [water("id1", "W")],
                "ElectricMeterAsset": [electric("id2", "E")]]
        def plan = exporter.plan(request([
                "WaterMeterAsset"   : ["currentReading"],
                "ElectricMeterAsset": ["energy_active_total"]]), assets, false, NOW)
        def out = new ByteArrayOutputStream()

        when:
        exporter.write(plan, out)

        then:
        thrown(IllegalStateException)

        when: "the bytes are read back"
        def names = []
        def complete = true
        try {
            new ZipInputStream(new ByteArrayInputStream(out.toByteArray())).withCloseable { zip ->
                def entry
                while ((entry = zip.nextEntry) != null) {
                    names << entry.name
                    zip.readAllBytes()
                }
            }
        } catch (IOException ignored) {
            complete = false
        }

        then: "only the first entry reads back, or reading fails; no central directory was written"
        names.size() <= 1 || !complete
        !hasCentralDirectory(out.toByteArray())
    }

    /** The end-of-central-directory signature PK\5\6 is written only by finish(). */
    static boolean hasCentralDirectory(byte[] bytes) {
        for (int i = 0; i + 3 < bytes.length; i++) {
            if (bytes[i] == 0x50 && bytes[i + 1] == 0x4b && bytes[i + 2] == 0x05 && bytes[i + 3] == 0x06) {
                return true
            }
        }
        return false
    }

    def "limits concurrent exports"() {
        given:
        def exporter = new FleetExporter(new FakeRepository(), 1_000_000, 1)

        expect:
        exporter.tryAcquire()
        !exporter.tryAcquire()

        when:
        exporter.release()

        then:
        exporter.tryAcquire()
    }
}
