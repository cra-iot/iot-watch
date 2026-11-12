package org.openremote.test.iotwatch

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.openremote.manager.iotwatch.FleetExporter
import org.openremote.manager.iotwatch.FleetResourceImpl
import org.openremote.model.asset.Asset
import org.openremote.model.iotwatch.FleetExportReport
import org.openremote.model.iotwatch.FleetExportRequest
import org.openremote.model.iotwatch.FleetQuery
import org.openremote.model.iotwatch.FleetRow
import org.openremote.model.query.AssetQuery
import org.openremote.model.util.ValueUtil
import org.openremote.model.watermeter.WaterMeterAsset
import spock.lang.Specification

import java.time.Instant

/**
 * FleetResourceImpl.export without a container: the search, the authorized loads and the caller
 * are stubbed, everything between them (selection checks, plan, error responses) is the real code.
 */
class FleetExportResourceTest extends Specification {

    static final Instant NOW = Instant.parse("2026-11-12T09:30:00Z")

    def setupSpec() {
        ValueUtil.initialise(null)
    }

    /** Devices the stubbed search matches, keyed by id; loads answer from them through the select. */
    static class StubResource extends FleetResourceImpl {
        List<Asset<?>> devices = []
        boolean restricted
        List<AssetQuery> loads = []

        StubResource(FleetExporter exporter) {
            super(null, null, null, null, exporter)
        }

        @Override
        protected String realmOf(FleetQuery fleetQuery) {
            return "test-realm"
        }

        @Override
        protected List<FleetRow> matchedRows(FleetQuery fleetQuery, String realm) {
            return devices.collect { asset ->
                def row = new FleetRow()
                row.id = asset.id
                row.name = asset.name
                row.type = asset.type
                row
            }
        }

        @Override
        protected List<Asset<?>> authorizedFindAll(AssetQuery assetQuery, String realm) {
            loads << assetQuery
            def ids = assetQuery.ids as Set
            return devices.findAll { ids.contains(it.id) }
        }

        @Override
        protected Instant now() {
            return NOW
        }

        @Override
        boolean isRestrictedUser() {
            return restricted
        }

        @Override
        String getUsername() {
            return "test-user"
        }
    }

    def exporter = new FleetExporter(new FleetExporterTest.FakeRepository(), 1_000_000, 2)
    def resource = new StubResource(exporter)

    static FleetExportRequest request(String assetType, String to = "2026-10-02") {
        return FleetExporterTest.request(assetType, FleetExportReport.DAILY, "2026-10-01", to)
    }

    static void assertTextEntity(WebApplicationException e, int status, String message) {
        assert e.response.status == status
        assert e.response.entity == message
        assert e.response.mediaType.isCompatible(MediaType.TEXT_PLAIN_TYPE)
    }

    def "a missing request is a 400 carrying its message"() {
        when:
        resource.export(null)

        then:
        def e = thrown(BadRequestException)
        assertTextEntity(e, 400, "Export request required")
    }

    def "an invalid range is a 400 carrying the range message"() {
        given:
        resource.devices = [FleetExporterTest.water("id1", "A")]

        when:
        resource.export(request("WaterMeterAsset", "2026-11-12"))

        then:
        def e = thrown(BadRequestException)
        assertTextEntity(e, 400, "The last day must be yesterday or earlier")
    }

    def "a malformed search is a 400 carrying its message"() {
        given:
        def request = request("WaterMeterAsset")
        request.query.status = ["sleeping"]

        when:
        resource.export(request)

        then:
        def e = thrown(BadRequestException)
        assertTextEntity(e, 400, "Unknown status: sleeping")
    }

    def "a type without readings a restricted caller can read is a 403 carrying its message"() {
        given:
        resource.devices = [FleetExporterTest.water("id1", "A")]
        resource.restricted = true
        def restore = FleetExporterTest.hideFromRestricted(WaterMeterAsset.CURRENT_READING_ATTRIBUTE_DESCRIPTOR)

        when:
        resource.export(request("WaterMeterAsset"))

        then:
        def e = thrown(ForbiddenException)
        assertTextEntity(e, 403, "No readable readings for WaterMeterAsset")

        cleanup:
        restore()
    }

    def "the options endpoint reports a malformed search with its message"() {
        given:
        def query = new FleetQuery()
        query.status = ["sleeping"]

        when:
        resource.exportOptions(query)

        then:
        def e = thrown(BadRequestException)
        assertTextEntity(e, 400, "Unknown status: sleeping")
    }

    def "a search with matches validates the selection"() {
        given:
        resource.devices = [FleetExporterTest.water("id1", "A")]
        resource.restricted = restricted

        when:
        resource.export(request(assetType))

        then:
        def e = thrown(BadRequestException)
        assertTextEntity(e, 400, message)
        resource.loads.isEmpty()

        where:
        assetType            | restricted | message
        null                 | false      | "Select a meter type"
        "ElectricMeterAsset" | false      | "No matched devices of type ElectricMeterAsset"
        "ElectricMeterAsset" | true       | "No matched devices of type ElectricMeterAsset"
    }

    def "a search without matches gives a header-only csv"() {
        when:
        Response response = resource.export(request("WaterMeterAsset"))
        def out = new ByteArrayOutputStream()
        (response.entity as jakarta.ws.rs.core.StreamingOutput).write(out)

        then:
        response.status == 200
        response.getHeaderString("Content-Type") == FleetExporter.CSV_CONTENT_TYPE
        FleetExporterTest.lines(out.toByteArray()) == ["date,assetId,name,meter_id,devEui,external_id,delivery_point_id," +
                "end_reading_at,volume_start_m3,volume_end_m3,volume_consumption_m3"]
        resource.loads.isEmpty()
    }

    def "only the matched ids of the requested type are loaded, with identity and register attributes"() {
        given:
        resource.devices = [FleetExporterTest.water("id1", "A"), FleetExporterTest.electric("id2", "E")]

        when:
        resource.export(request("WaterMeterAsset"))

        then:
        resource.loads.size() == 1
        resource.loads[0].ids as List == ["id1"]
        resource.loads[0].select.attributes as List == ["meter_id", "devEui", "external_id", "delivery_point_id", "currentReading"]
    }

    def "a valid export streams with the plan's content type"() {
        given:
        resource.devices = [FleetExporterTest.water("id1", "A")]

        when:
        Response response = resource.export(request("WaterMeterAsset"))
        def out = new ByteArrayOutputStream()
        (response.entity as jakarta.ws.rs.core.StreamingOutput).write(out)

        then:
        response.status == 200
        response.getHeaderString("Content-Type") == FleetExporter.CSV_CONTENT_TYPE
        FleetExporterTest.lines(out.toByteArray()).size() == 3
    }

    def "a valid monthly export streams a CSV named for the type"() {
        given:
        resource.devices = [FleetExporterTest.water("id1", "A")]

        when:
        Response response = resource.export(
                FleetExporterTest.request("WaterMeterAsset", FleetExportReport.MONTHLY, "2026-08-01", "2026-10-01"))
        def out = new ByteArrayOutputStream()
        (response.entity as jakarta.ws.rs.core.StreamingOutput).write(out)

        then:
        response.status == 200
        response.getHeaderString("Content-Type") == "text/csv; charset=UTF-8"
        response.getHeaderString("Content-Disposition").contains("WaterMeterAsset_monthly_")
        response.getHeaderString("Content-Disposition").contains("_2026-08_2026-10_")
        FleetExporterTest.lines(out.toByteArray()).size() == 4
    }

    def "a header-only export is logged with its sanitised requested type"() {
        given:
        def records = []
        def handler = new java.util.logging.Handler() {
            void publish(java.util.logging.LogRecord record) { records << record.message }
            void flush() {}
            void close() {}
        }
        def logger = java.util.logging.Logger.getLogger(FleetResourceImpl.name)
        logger.addHandler(handler)

        when:
        def response = resource.export(request(type))
        def out = new ByteArrayOutputStream()
        (response.entity as jakarta.ws.rs.core.StreamingOutput).write(out)

        then:
        records.any { it.contains("Fleet export ") && it.contains(" type=" + logged + " ") && it.contains("outcome=ok") }

        cleanup:
        logger.removeHandler(handler)

        where:
        type           | logged
        "TrackerAsset" | "TrackerAsset"
        "  "           | "-"
        "A\nB"         | "A?B"
        " WaterMeterAsset " | "WaterMeterAsset"
    }

    def "a busy exporter is a 429 carrying its message as text"() {
        given:
        resource.devices = [FleetExporterTest.water("id1", "A")]
        def busy = new FleetExporter(new FleetExporterTest.FakeRepository(), 1_000_000, 1)
        def busyResource = new StubResource(busy)
        busyResource.devices = resource.devices
        assert busy.tryAcquire() != null

        when:
        busyResource.export(request("WaterMeterAsset"))

        then:
        def e = thrown(WebApplicationException)
        assertTextEntity(e, 429, "Another export is running, try again in a moment")
    }

    def "a rejected export is logged with its status and reason"() {
        given:
        def records = []
        def handler = new java.util.logging.Handler() {
            void publish(java.util.logging.LogRecord record) { records << record.message }
            void flush() {}
            void close() {}
        }
        def logger = java.util.logging.Logger.getLogger(FleetResourceImpl.name)
        logger.addHandler(handler)

        when:
        resource.export(null)

        then:
        thrown(BadRequestException)
        records.any { it.contains("status=400") && it.contains("reason=Export request required") && it.contains("outcome=rejected") }

        cleanup:
        logger.removeHandler(handler)
    }
}
