package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.FleetExportCsv
import org.openremote.manager.iotwatch.FleetExportPeriod
import org.openremote.manager.iotwatch.FleetExporter
import org.openremote.manager.iotwatch.FleetResourceImpl
import org.openremote.manager.iotwatch.PeriodRows
import org.openremote.manager.iotwatch.PeriodValueRepository
import org.openremote.model.asset.Asset
import org.openremote.model.attribute.MetaItem
import org.openremote.model.electricmeter.ElectricMeterAsset
import org.openremote.model.gasmeter.GasMeterAsset
import org.openremote.model.iotwatch.FleetExportReport
import org.openremote.model.iotwatch.FleetExportRequest
import org.openremote.model.iotwatch.FleetQuery
import org.openremote.model.util.ValueUtil
import org.openremote.model.value.MetaItemType
import org.openremote.model.watermeter.WaterMeterAsset
import spock.lang.Specification

import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.function.LongSupplier

class FleetExporterTest extends Specification {

    static final Instant NOW = Instant.parse("2026-11-12T09:30:00Z")
    static final String BOM = new String(FleetExportCsv.UTF8_BOM, StandardCharsets.UTF_8)

    def setupSpec() {
        ValueUtil.initialise(null)
    }

    /**
     * For every requested asset and attribute: seed 5 one hour before the range, then 10, 20, 30...
     * one hour into each period (t1 and t2 one and two minutes later). empty: no readings at all;
     * failOn: fails for that attribute.
     */
    static class FakeRepository extends PeriodValueRepository {
        String failOn
        boolean empty
        List<List<String>> queried = []
        static final Map<String, Integer> MINUTES = ["energy_active_t1": 1, "energy_active_t2": 2]

        FakeRepository() {
            super(null, 0)
        }

        @Override
        Map<String, Map<LocalDate, Map<String, PeriodRows.Reading>>> lastPerPeriod(
                List<String> assetIds, List<String> attributes, FleetExportPeriod period) {
            queried << attributes
            if (failOn != null && attributes.contains(failOn)) {
                throw new IllegalStateException("database gone")
            }
            if (empty) {
                return [:]
            }
            def result = [:]
            assetIds.each { id ->
                def perPeriod = [:]
                period.periods().eachWithIndex { LocalDate start, int i ->
                    def at = start.atStartOfDay(FleetExportPeriod.ZONE).toInstant().plusSeconds(3600)
                    perPeriod[start] = attributes.collectEntries {
                        [(it): new PeriodRows.Reading(String.valueOf(10 * (i + 1)), at.plusSeconds(60 * MINUTES.getOrDefault(it, 0)))]
                    }
                }
                result[id] = perPeriod
            }
            return result
        }

        @Override
        Map<String, Map<String, PeriodRows.Reading>> seed(
                List<String> assetIds, List<String> attributes, FleetExportPeriod period) {
            if (empty) {
                return [:]
            }
            return assetIds.collectEntries { id ->
                [(id): attributes.collectEntries { [(it): new PeriodRows.Reading("5", period.start().minusSeconds(3600))] }]
            }
        }
    }

    static WaterMeterAsset water(String id, String name) {
        def asset = new WaterMeterAsset(name)
        asset.setId(id)
        asset.getAttributes().getOrCreate(WaterMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR).setValue("TEST-EUI-" + id)
        asset.getAttributes().getOrCreate(WaterMeterAsset.METER_ID_ATTRIBUTE_DESCRIPTOR).setValue("M-" + id)
        asset.getAttributes().getOrCreate(WaterMeterAsset.CURRENT_READING_ATTRIBUTE_DESCRIPTOR)
        return asset
    }

    static ElectricMeterAsset electric(String id, String name) {
        def asset = new ElectricMeterAsset(name)
        asset.setId(id)
        asset.getAttributes().getOrCreate(ElectricMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR).setValue("TEST-EUI-" + id)
        asset.getAttributes().getOrCreate(ElectricMeterAsset.ENERGY_ACTIVE_TOTAL_ATTRIBUTE_DESCRIPTOR)
        asset.getAttributes().getOrCreate(ElectricMeterAsset.ENERGY_ACTIVE_T1_ATTRIBUTE_DESCRIPTOR)
        asset.getAttributes().getOrCreate(ElectricMeterAsset.ENERGY_ACTIVE_T2_ATTRIBUTE_DESCRIPTOR)
        return asset
    }

    static GasMeterAsset gas(String id, String name) {
        def asset = new GasMeterAsset(name)
        asset.setId(id)
        asset.getAttributes().getOrCreate(GasMeterAsset.VOLUME_ATTRIBUTE_DESCRIPTOR)
        return asset
    }

    static FleetExportRequest request(String assetType, FleetExportReport report = FleetExportReport.DAILY,
                                      String from = "2026-10-01", String to = "2026-10-02") {
        def request = new FleetExportRequest()
        request.query = new FleetQuery()
        request.assetType = assetType
        request.report = report
        request.from = from
        request.to = to
        return request
    }

    static List<String> lines(byte[] bytes) {
        def text = new String(bytes, StandardCharsets.UTF_8)
        assert text.startsWith(BOM)
        return text.substring(BOM.length()).split("\r\n").toList()
    }

    /** Takes ACCESS_RESTRICTED_READ off a descriptor until the returned closure restores it. */
    static Closure hideFromRestricted(def descriptor) {
        descriptor.getMeta().remove(MetaItemType.ACCESS_RESTRICTED_READ)
        return { descriptor.getMeta().addOrReplace(new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ)) }
    }

    def exporter = new FleetExporter(new FakeRepository(), 1_000_000, 2)

    /** Loads recorded by the loader of planFor(): type, ids, attributes. */
    List<List> loads = []

    /**
     * Plans the way the endpoint does: the matched ids per type come from the search, and the
     * loader answers with the matched assets of a type (here: all of them, as an admin sees them).
     */
    FleetExporter.ExportPlan planFor(FleetExporter exporter, FleetExportRequest request,
                                     Map<String, List<Asset<?>>> devices, boolean restricted = false) {
        Map<String, Set<String>> matchedIds = devices.collectEntries { type, assets ->
            [(type): assets.collect { it.id } as LinkedHashSet<String>]
        }
        return exporter.plan(request, matchedIds, { String type, Set<String> ids, List<String> attributes ->
            loads << [type, ids, attributes]
            return devices[type].findAll { ids.contains(it.id) }
        } as FleetExporter.AssetLoader, restricted, NOW)
    }

    byte[] export(FleetExporter.ExportPlan plan) {
        def out = new ByteArrayOutputStream()
        exporter.write(plan, out)
        return out.toByteArray()
    }

    def "monthly water export writes one row per meter and month"() {
        given:
        Map<String, List<Asset<?>>> devices = ["WaterMeterAsset": [water("id2", "B"), water("id1", "A")]]
        def plan = planFor(exporter, request("WaterMeterAsset", FleetExportReport.MONTHLY, "2026-09-01", "2026-10-01"), devices)
        def out = new ByteArrayOutputStream()

        when:
        def rows = exporter.write(plan, out)
        def lines = lines(out.toByteArray())

        then:
        rows == 4
        lines.size() == 5
        lines[0].split(",").toList() == ["month", "assetId", "name", "meter_id", "devEui", "external_id", "delivery_point_id",
                                         "end_reading_at", "volume_start_m3", "volume_end_m3", "volume_consumption_m3"]
        lines[1].startsWith("2026-09,")
        lines[1] == "2026-09,id1,A,\"=\"\"M-id1\"\"\",\"=\"\"TEST-EUI-id1\"\"\",,,2026-08-31T23:00:00Z,5,10,5"
        lines[2] == "2026-10,id1,A,\"=\"\"M-id1\"\"\",\"=\"\"TEST-EUI-id1\"\"\",,,2026-09-30T23:00:00Z,10,20,10"
        lines.drop(1).collect { it.split(",")[1] } == ["id1", "id1", "id2", "id2"]
        loads == [["WaterMeterAsset", ["id2", "id1"] as LinkedHashSet,
                   ["meter_id", "devEui", "external_id", "delivery_point_id", "currentReading"]]]
        exporter.contentType(plan) == "text/csv; charset=UTF-8"
    }

    def "electricity rows carry total, t1 and t2 groups"() {
        given:
        def plan = planFor(exporter, request("ElectricMeterAsset"), ["ElectricMeterAsset": [electric("id1", "E")]])

        when:
        def lines = lines(export(plan))

        then:
        lines[0].endsWith(",end_reading_at,total_start_kWh,total_end_kWh,total_consumption_kWh," +
                "t1_start_kWh,t1_end_kWh,t1_consumption_kWh,t2_start_kWh,t2_end_kWh,t2_consumption_kWh")
        lines[1] == "2026-10-01,id1,E,,\"=\"\"TEST-EUI-id1\"\"\",,,2026-09-30T23:00:00Z,5,10,5,5,10,5,5,10,5"
    }

    def "a group the asset lacks is written empty"() {
        given:
        def meter = electric("id1", "E")
        meter.getAttributes().remove("energy_active_t2")
        def repository = new FakeRepository()
        def exporter = new FleetExporter(repository, 1_000_000, 2)
        def plan = planFor(exporter, request("ElectricMeterAsset"), ["ElectricMeterAsset": [meter]])
        def out = new ByteArrayOutputStream()

        when:
        exporter.write(plan, out)
        def lines = lines(out.toByteArray())

        then:
        lines[0].split(",").size() == 17
        lines[1] == "2026-10-01,id1,E,,\"=\"\"TEST-EUI-id1\"\"\",,,2026-09-30T23:00:00Z,5,10,5,5,10,5,,,"
        repository.queried.every { !it.contains("energy_active_t2") }
    }

    def "values are filtered per asset to the attributes it has"() {
        given:
        def withT2 = electric("id1", "A")
        def withoutT2 = electric("id2", "B")
        withoutT2.getAttributes().remove("energy_active_t2")
        def plan = planFor(exporter, request("ElectricMeterAsset"), ["ElectricMeterAsset": [withT2, withoutT2]])

        when:
        def lines = lines(export(plan))

        then:
        lines[1] == "2026-10-01,id1,A,,\"=\"\"TEST-EUI-id1\"\"\",,,2026-09-30T23:00:00Z,5,10,5,5,10,5,5,10,5"
        lines[3] == "2026-10-01,id2,B,,\"=\"\"TEST-EUI-id2\"\"\",,,2026-09-30T23:00:00Z,5,10,5,5,10,5,,,"
    }

    def "end_reading_at follows the first group the asset has"() {
        given:
        def meter = electric("id1", "E")
        meter.getAttributes().remove("energy_active_total")
        def plan = planFor(exporter, request("ElectricMeterAsset"), ["ElectricMeterAsset": [meter]])

        when:
        def lines = lines(export(plan))

        then:
        lines[1] == "2026-10-01,id1,E,,\"=\"\"TEST-EUI-id1\"\"\",,,2026-09-30T23:01:00Z,,,,5,10,5,5,10,5"
    }

    def "a meter without readings still gets every row"() {
        given:
        def exporter = new FleetExporter(new FakeRepository(empty: true), 1_000_000, 2)
        def plan = planFor(exporter, request("WaterMeterAsset", FleetExportReport.DAILY, "2026-10-01", "2026-10-03"),
                ["WaterMeterAsset": [water("id1", "A")]])
        def out = new ByteArrayOutputStream()

        when:
        def rows = exporter.write(plan, out)
        def lines = lines(out.toByteArray())

        then:
        rows == 3
        lines.drop(1) == [
                "2026-10-01,id1,A,\"=\"\"M-id1\"\"\",\"=\"\"TEST-EUI-id1\"\"\",,,,,,",
                "2026-10-02,id1,A,\"=\"\"M-id1\"\"\",\"=\"\"TEST-EUI-id1\"\"\",,,,,,",
                "2026-10-03,id1,A,\"=\"\"M-id1\"\"\",\"=\"\"TEST-EUI-id1\"\"\",,,,,,"]
    }

    def "a type no longer matched is a 400"() {
        when:
        planFor(exporter, request("WaterMeterAsset"), ["GasMeterAsset": [gas("id1", "G")]])

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "No matched devices of type WaterMeterAsset"
        loads.isEmpty()
    }

    def "plan rejects"() {
        when:
        planFor(exporter, request(assetType), ["WaterMeterAsset": [water("id1", "W")], "TrackerAsset": [water("id2", "T")]])

        then:
        def e = thrown(IllegalArgumentException)
        e.message == message
        loads.isEmpty()

        where:
        assetType      | message
        null           | "Select a meter type"
        " "            | "Select a meter type"
        "TrackerAsset" | "Meter type TrackerAsset has no readings to export"
    }

    def "the period is validated first"() {
        when:
        planFor(exporter, request("WaterMeterAsset", FleetExportReport.DAILY, "2026-10-01", "2026-11-12"), [:])

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "The last day must be yesterday or earlier"
    }

    def "an empty search gives a header-only CSV"() {
        given:
        def plan = planFor(exporter, request("WaterMeterAsset"), [:])
        def out = new ByteArrayOutputStream()

        when:
        def rows = exporter.write(plan, out)

        then:
        rows == 0
        new String(out.toByteArray(), StandardCharsets.UTF_8) == BOM + "date,assetId,name,meter_id,devEui,external_id," +
                "delivery_point_id,end_reading_at,volume_start_m3,volume_end_m3,volume_consumption_m3\r\n"
        loads.isEmpty()
        exporter.fileName(plan).startsWith("WaterMeterAsset_daily_")
        exporter.contentType(plan) == "text/csv; charset=UTF-8"
    }

    def "an empty search for a type without readings has only the fixed columns"() {
        given:
        def plan = planFor(exporter, request(assetType), [:])
        def out = new ByteArrayOutputStream()

        when:
        exporter.write(plan, out)

        then:
        lines(out.toByteArray()) == ["date,assetId,name,meter_id,devEui,external_id,delivery_point_id,end_reading_at"]
        exporter.fileName(plan).startsWith("fleet-export_daily_2026-10-01_2026-10-02_")

        where:
        assetType << [null, "TrackerAsset", "bad\"name"]
    }

    def "row cap counts devices times periods"() {
        given:
        def exporter = new FleetExporter(new FakeRepository(), 23, 2)

        when:
        planFor(exporter, request("WaterMeterAsset", FleetExportReport.MONTHLY, "2024-11-01", "2026-10-01"),
                ["WaterMeterAsset": [water("id1", "W")]])

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "Export too large: narrow the search or the period"
        loads.isEmpty()
    }

    def "row cap allows exactly the limit"() {
        given:
        def exporter = new FleetExporter(new FakeRepository(), 24, 2)

        when:
        def plan = planFor(exporter, request("WaterMeterAsset", FleetExportReport.MONTHLY, "2024-11-01", "2026-10-01"),
                ["WaterMeterAsset": [water("id1", "W")]])

        then:
        plan.devices() == 1
    }

    def "restricted caller without readable groups is a 403"() {
        given:
        def restore = hideFromRestricted(WaterMeterAsset.CURRENT_READING_ATTRIBUTE_DESCRIPTOR)

        when:
        planFor(exporter, request("WaterMeterAsset"), ["WaterMeterAsset": [water("id1", "W")]], true)

        then:
        def e = thrown(SecurityException)
        e.message == "No readable readings for WaterMeterAsset"
        loads.isEmpty()

        cleanup:
        restore()
    }

    def "a restricted caller gets the readable groups"() {
        when:
        def plan = planFor(exporter, request("ElectricMeterAsset"), ["ElectricMeterAsset": [electric("id1", "E")]], true)

        then:
        plan.groups()*.key() == ["total", "t1", "t2"]
    }

    def "file name"() {
        expect:
        exporter.fileName(planFor(exporter, request("WaterMeterAsset", FleetExportReport.MONTHLY, "2025-11-01", "2026-10-01"),
                ["WaterMeterAsset": [water("id1", "W")]])) == "WaterMeterAsset_monthly_2025-11_2026-10_20261112T0930Z.csv"
        exporter.fileName(planFor(exporter, request("WaterMeterAsset", FleetExportReport.DAILY, "2026-10-01", "2026-10-31"),
                ["WaterMeterAsset": [water("id1", "W")]])) == "WaterMeterAsset_daily_2026-10-01_2026-10-31_20261112T0930Z.csv"
    }

    def "max concurrent exports is at least one"() {
        given:
        def exporter = new FleetExporter(new FakeRepository(), 1_000_000, configured)

        expect:
        exporter.tryAcquire() != null
        exporter.tryAcquire() == null

        where:
        configured << [0, -3]
    }

    /** Keeps what had been flushed when the repository is first queried. */
    static class FlushRecordingStream extends ByteArrayOutputStream {
        byte[] flushed = new byte[0]

        @Override
        void flush() {
            flushed = toByteArray()
        }
    }

    def "the header is flushed before the first batch is queried"() {
        given:
        def out = new FlushRecordingStream()
        byte[] flushedAtQuery = null
        def repository = new FakeRepository() {
            @Override
            Map<String, Map<LocalDate, Map<String, PeriodRows.Reading>>> lastPerPeriod(
                    List<String> assetIds, List<String> attributes, FleetExportPeriod period) {
                flushedAtQuery = out.flushed
                return super.lastPerPeriod(assetIds, attributes, period)
            }
        }
        def exporter = new FleetExporter(repository, 1_000_000, 2)
        def plan = planFor(exporter, request("WaterMeterAsset"), ["WaterMeterAsset": [water("id1", "W")]])

        when:
        exporter.write(plan, out)

        then:
        new String(flushedAtQuery, StandardCharsets.UTF_8) == BOM + "date,assetId,name,meter_id,devEui,external_id," +
                "delivery_point_id,end_reading_at,volume_start_m3,volume_end_m3,volume_consumption_m3\r\n"
    }

    def "a failure while writing propagates and leaves the csv truncated"() {
        given:
        def exporter = new FleetExporter(new FakeRepository(failOn: "currentReading"), 1_000_000, 2)
        def plan = planFor(exporter, request("WaterMeterAsset"), ["WaterMeterAsset": [water("id1", "W")]])
        def out = new ByteArrayOutputStream()

        when:
        exporter.write(plan, out)

        then:
        thrown(IllegalStateException)
        lines(out.toByteArray()).size() == 1
    }

    def "limits concurrent exports"() {
        given:
        def exporter = new FleetExporter(new FakeRepository(), 1_000_000, 1)

        def permit = exporter.tryAcquire()

        expect:
        permit != null
        exporter.tryAcquire() == null

        when:
        permit.close()

        then:
        exporter.tryAcquire() != null
    }

    def "closing a permit twice frees only its own slot"() {
        given:
        def exporter = new FleetExporter(new FakeRepository(), 1_000_000, 2)
        def first = exporter.tryAcquire()
        exporter.tryAcquire()

        when:
        first.close()
        first.close()

        then:
        exporter.tryAcquire() != null
        exporter.tryAcquire() == null
    }

    def "a permit held past the timeout is reclaimed, and its late close frees nothing"() {
        given:
        long now = 0
        def exporter = new FleetExporter(new FakeRepository(), 1_000_000, 1, Duration.ofMinutes(60), { now } as LongSupplier)
        def lost = exporter.tryAcquire()

        when:
        now = Duration.ofMinutes(59).toNanos()

        then:
        exporter.tryAcquire() == null

        when:
        now = Duration.ofMinutes(60).toNanos()
        def next = exporter.tryAcquire()

        then:
        next != null

        when:
        lost.close()

        then:
        exporter.tryAcquire() == null
    }

    /** Records connection aborts instead of touching a real Undertow exchange. */
    static class AbortRecordingResource extends FleetResourceImpl {
        int aborts

        AbortRecordingResource(FleetExporter exporter) {
            super(null, null, null, null, exporter)
        }

        @Override
        protected void abortConnection() {
            aborts++
        }
    }

    def "a failed export body aborts the connection, releases the permit and rethrows"() {
        given:
        def exporter = new FleetExporter(new FakeRepository(failOn: "currentReading"), 1_000_000, 1)
        def resource = new AbortRecordingResource(exporter)
        def plan = planFor(exporter, request("WaterMeterAsset"), ["WaterMeterAsset": [water("id1", "W")]])
        def permit = exporter.tryAcquire()
        assert permit != null

        when:
        resource.exportBody(plan, permit, "test-user", "test-realm", System.currentTimeMillis())
                .write(new ByteArrayOutputStream())

        then:
        thrown(IllegalStateException)
        resource.aborts == 1
        exporter.tryAcquire() != null
    }

    def "a successful export body leaves the connection alone and releases the permit"() {
        given:
        def exporter = new FleetExporter(new FakeRepository(), 1_000_000, 1)
        def resource = new AbortRecordingResource(exporter)
        def plan = planFor(exporter, request("WaterMeterAsset"), ["WaterMeterAsset": [water("id1", "W")]])
        def permit = exporter.tryAcquire()
        assert permit != null
        def out = new ByteArrayOutputStream()

        when:
        resource.exportBody(plan, permit, "test-user", "test-realm", System.currentTimeMillis()).write(out)

        then:
        resource.aborts == 0
        lines(out.toByteArray()).size() == 3
        exporter.tryAcquire() != null
    }
}
