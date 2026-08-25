package org.openremote.test.iotwatch

import groovy.json.JsonOutput
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.datapoint.AssetDatapointService
import org.openremote.manager.iotwatch.IngestMetrics
import org.openremote.manager.iotwatch.IotWatchIngestService
import org.openremote.model.Constants
import org.openremote.model.attribute.AttributeRef
import org.openremote.model.tracker.TrackerAsset
import org.openremote.model.watermeter.WaterMeterAsset
import org.openremote.test.ManagerContainerTrait
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

// Boots the OpenRemote container (ManagerContainerTrait), which needs a running PostgreSQL and Keycloak.
// The bare CI runner provides neither, so the container start blocks on the DB connection timeout.
// Runs only when OR_CONTAINER_TESTS=true (i.e. the dev stack is up); otherwise the spec is skipped.
@IgnoreIf({ env['OR_CONTAINER_TESTS'] != 'true' })
class IngestEndpointTest extends Specification implements ManagerContainerTrait {

    static final String TEST_KEY = "dummy-test-key-not-a-secret"
    static final String EUI = "00112233AABBCCDD"

    static String envelope(String eui, Long ts) {
        def inner = [cmd: "gw", data: "cbe006e001c10106aa7fff", fcnt: 2900, port: 2, bat: 254, EUI: eui]
        if (ts != null) {
            inner.ts = ts
        }
        return JsonOutput.toJson([data: JsonOutput.toJson(inner), tags: [], tech: "L", type: "D"])
    }

    static HttpResponse<String> post(int port, String realm, String key, String body) {
        def builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${port}/api/${realm}/ingest"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (key != null) {
            builder.header("X-API-Key", key)
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    static TrackerAsset trackerWithEui(String name, String eui) {
        def asset = new TrackerAsset(name)
        asset.setRealm(Constants.MASTER_REALM)
        asset.getAttributes().getOrCreate(TrackerAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR).setValue(eui)
        return asset
    }

    static WaterMeterAsset waterMeterWithEui(String name, String eui) {
        def asset = new WaterMeterAsset(name)
        asset.setRealm(Constants.MASTER_REALM)
        asset.getAttributes().getOrCreate(WaterMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR).setValue(eui)
        asset.getAttributes().getOrCreate(WaterMeterAsset.CURRENT_READING_ATTRIBUTE_DESCRIPTOR)
        return asset
    }

    /** An envelope whose decoded payload carries one reading with an explicit measurement time. */
    static String readingEnvelope(String eui, long messageTs, Long measuredAt, double currentReading) {
        def reading = [currentReading: currentReading]
        if (measuredAt != null) {
            reading.measured_at = measuredAt
        }
        def inner = [cmd: "gw", EUI: eui, ts: messageTs, data_decoded: reading]
        return JsonOutput.toJson([data: JsonOutput.toJson(inner), tags: [], tech: "L", type: "D"])
    }

    /**
     * An envelope whose decoded payload carries several readings, each with its own measurement
     * time, as {@code [[measuredAt, currentReading], ...]}. Because the envelope is real JSON,
     * {@code data_decoded.readings} reaches the router as the {@code Object[]} Jackson produces
     * for an untyped JSON array (ValueUtil enables USE_JAVA_ARRAY_FOR_JSON_ARRAY) — the
     * representation the production path actually carries, which a Groovy list literal does not
     * reproduce.
     */
    static String readingsEnvelope(String eui, long messageTs, List<List> measuredAtAndReading) {
        def readings = measuredAtAndReading.collect { [measured_at: it[0], currentReading: it[1]] }
        def inner = [cmd: "gw", EUI: eui, ts: messageTs, data_decoded: [readings: readings]]
        return JsonOutput.toJson([data: JsonOutput.toJson(inner), tags: [], tech: "L", type: "D"])
    }

    def "ingest endpoint authenticates, matches devEui and writes rawValue"() {
        given: "a container with the ingest key configured for the master realm"
        def conditions = new PollingConditions(timeout: 15, delay: 0.5)
        def serverPort = findEphemeralPort()
        def config = defaultConfig(serverPort)
        config.put(IotWatchIngestService.OR_IOTWATCH_INGEST_KEYS, "${Constants.MASTER_REALM}:${TEST_KEY}".toString())
        config.put("OR_METRICS_ENABLED", "true")
        config.put("OR_METRICS_PORT", String.valueOf(findEphemeralPort()))
        def container = startContainer(config, defaultServices())
        def assetStorageService = container.getService(AssetStorageService.class)

        and: "a tracker asset with a devEui (created after service start: exercises cache freshness)"
        def tracker = assetStorageService.merge(trackerWithEui("Ingest test tracker", EUI))

        and: "a device timestamp from the container clock, not older than the merge (AssetProcessingService discards events older than the attribute's last-updated timestamp as outdated; the merge stamps attributes with the pseudo clock, which ContainerTrait advances 10 ms past wall-clock time, so System.currentTimeMillis() taken here could be older)"
        def deviceTs = getClockTimeOf(container)

        expect: "the container is running"
        conditions.eventually {
            assert container.isRunning()
        }

        when: "posting without a key"
        def response = post(serverPort, Constants.MASTER_REALM, null, envelope(EUI, deviceTs))

        then:
        response.statusCode() == 401

        when: "posting with a wrong key"
        response = post(serverPort, Constants.MASTER_REALM, "wrong-key", envelope(EUI, deviceTs))

        then:
        response.statusCode() == 401

        when: "posting a valid key for a different realm path"
        response = post(serverPort, "unknownrealm", TEST_KEY, envelope(EUI, deviceTs))

        then:
        response.statusCode() == 401

        when: "posting an envelope whose inner data is not JSON"
        response = post(serverPort, Constants.MASTER_REALM, TEST_KEY,
            JsonOutput.toJson([data: "not json", tags: [], tech: "L", type: "D"]))

        then:
        response.statusCode() == 400

        when: "posting for an unknown device"
        response = post(serverPort, Constants.MASTER_REALM, TEST_KEY, envelope("0000000000000000", deviceTs))

        then:
        response.statusCode() == 404

        when: "posting a valid message"
        response = post(serverPort, Constants.MASTER_REALM, TEST_KEY, envelope(EUI, deviceTs))

        then: "the request is accepted and rawValue is written with the device timestamp"
        response.statusCode() == 200
        conditions.eventually {
            def updated = assetStorageService.find(tracker.getId(), true) as TrackerAsset
            def rawValue = updated.getAttribute(TrackerAsset.RAW_VALUE_ATTRIBUTE_DESCRIPTOR).orElse(null)
            assert rawValue != null
            assert rawValue.getValue().isPresent()
            assert (rawValue.getValue().get() as Map).get("bat") == 254
            assert rawValue.getTimestamp().orElse(0L) == deviceTs
        }

        when: "a second asset claims the same devEui"
        def duplicate = assetStorageService.merge(trackerWithEui("Duplicate tracker", EUI))

        then: "posting eventually fans out to both assets (persistence event updates the cache)"
        conditions.eventually {
            // Re-read the clock here (rather than reusing deviceTs): the duplicate asset is merged
            // after deviceTs was captured, so its rawValue attribute is stamped with a later pseudo-clock
            // reading, and a stale deviceTs would always be discarded as outdated for it (see the clock
            // hazard noted above for the first merge).
            def fanOutTs = getClockTimeOf(container)
            assert post(serverPort, Constants.MASTER_REALM, TEST_KEY, envelope(EUI, fanOutTs)).statusCode() == 200
            def dup = assetStorageService.find(duplicate.getId(), true) as TrackerAsset
            def dupRaw = dup.getAttribute(TrackerAsset.RAW_VALUE_ATTRIBUTE_DESCRIPTOR).orElse(null)
            assert dupRaw != null && dupRaw.getValue().isPresent()
            assert (dupRaw.getValue().get() as Map).get("bat") == 254
        }

        and: "the meters are visible in the container registry"
        def meterRegistry = container.getMeterRegistry()
        meterRegistry.get(IngestMetrics.REQUESTS_METER_NAME)
            .tags("realm", Constants.MASTER_REALM, "outcome", IngestMetrics.OUTCOME_ACCEPTED).counter().count() >= 1.0d
        meterRegistry.get(IngestMetrics.REQUESTS_METER_NAME)
            .tags("realm", Constants.MASTER_REALM, "outcome", IngestMetrics.OUTCOME_UNAUTHORIZED).counter().count() >= 1.0d
        meterRegistry.get(IngestMetrics.CACHE_SIZE_METER_NAME)
            .tags("realm", Constants.MASTER_REALM).gauge().value() >= 1.0d
    }

    def "endpoint is not registered when no keys are configured"() {
        given:
        def serverPort = findEphemeralPort()
        def container = startContainer(defaultConfig(serverPort), defaultServices())

        expect:
        container.isRunning()

        when:
        def response = post(serverPort, Constants.MASTER_REALM, TEST_KEY, envelope(EUI, null))

        then:
        response.statusCode() == 404
    }

    def "a backdated reading lands in history without moving the current value"() {
        given: "a container with the ingest key configured and a water meter asset"
        def conditions = new PollingConditions(timeout: 15, delay: 0.5)
        def serverPort = findEphemeralPort()
        def config = defaultConfig(serverPort)
        config.put(IotWatchIngestService.OR_IOTWATCH_INGEST_KEYS, "${Constants.MASTER_REALM}:${TEST_KEY}".toString())
        def container = startContainer(config, defaultServices())
        def assetStorageService = container.getService(AssetStorageService.class)
        def datapointService = container.getService(AssetDatapointService.class)
        def meter = assetStorageService.merge(waterMeterWithEui("Ingest test water meter", EUI))
        def now = getClockTimeOf(container)
        def yesterday = now - 86_400_000L

        when: "a reading measured now arrives"
        def response = post(serverPort, Constants.MASTER_REALM, TEST_KEY,
            readingEnvelope(EUI, now, now, 100.0d))

        then: "it becomes the current value"
        response.statusCode() == 200
        conditions.eventually {
            def updated = assetStorageService.find(meter.getId(), true) as WaterMeterAsset
            def attribute = updated.getAttribute(WaterMeterAsset.CURRENT_READING_ATTRIBUTE_DESCRIPTOR).orElse(null)
            assert attribute != null
            assert attribute.getValue().orElse(null) == 100.0d
            assert attribute.getTimestamp().orElse(0L) == now
        }

        when: "a reading measured yesterday is delivered afterwards"
        response = post(serverPort, Constants.MASTER_REALM, TEST_KEY,
            readingEnvelope(EUI, now + 1000L, yesterday, 90.0d))

        then: "history gains yesterday's point while the current value stays at today's"
        response.statusCode() == 200
        conditions.eventually {
            def points = datapointService.getDatapoints(
                new AttributeRef(meter.getId(), WaterMeterAsset.CURRENT_READING_ATTRIBUTE_DESCRIPTOR.getName()))
            assert points.any { it.timestamp == yesterday && (it.value as double) == 90.0d }
            assert points.any { it.timestamp == now && (it.value as double) == 100.0d }

            def updated = assetStorageService.find(meter.getId(), true) as WaterMeterAsset
            def attribute = updated.getAttribute(WaterMeterAsset.CURRENT_READING_ATTRIBUTE_DESCRIPTOR).orElse(null)
            assert attribute.getValue().orElse(null) == 100.0d
            assert attribute.getTimestamp().orElse(0L) == now
        }
    }

    def "a readings list from one message becomes one data point per measurement time"() {
        given: "a container with the ingest key configured and a water meter asset"
        def conditions = new PollingConditions(timeout: 15, delay: 0.5)
        def config = defaultConfig(null)
        config.put(IotWatchIngestService.OR_IOTWATCH_INGEST_KEYS, "${Constants.MASTER_REALM}:${TEST_KEY}".toString())
        def container = startContainer(config, defaultServices())
        // startContainer reuses an already-running container when the config and services match,
        // and deliberately ignores the listen port in that comparison. The preceding feature method
        // has an identical config, so the reused container keeps its original port — ask it which.
        def port = getServerPort()
        def assetStorageService = container.getService(AssetStorageService.class)
        def datapointService = container.getService(AssetDatapointService.class)
        def meter = assetStorageService.merge(waterMeterWithEui("Ingest test water meter", EUI))
        def now = getClockTimeOf(container)
        def anHourAgo = now - 3_600_000L

        when: "one message carries two readings for this meter, measured an hour apart"
        def response = post(port, Constants.MASTER_REALM, TEST_KEY,
            readingsEnvelope(EUI, now, [[anHourAgo, 90.0d], [now, 100.0d]]))

        then: "both readings are stored, each under its own measurement time"
        response.statusCode() == 200
        conditions.eventually {
            def points = datapointService.getDatapoints(
                new AttributeRef(meter.getId(), WaterMeterAsset.CURRENT_READING_ATTRIBUTE_DESCRIPTOR.getName()))
            assert points.any { it.timestamp == anHourAgo && (it.value as double) == 90.0d }
            assert points.any { it.timestamp == now && (it.value as double) == 100.0d }

            // the newest measurement is the current value; the backdated one only reaches history
            def updated = assetStorageService.find(meter.getId(), true) as WaterMeterAsset
            def attribute = updated.getAttribute(WaterMeterAsset.CURRENT_READING_ATTRIBUTE_DESCRIPTOR).orElse(null)
            assert attribute != null
            assert attribute.getValue().orElse(null) == 100.0d
            assert attribute.getTimestamp().orElse(0L) == now
        }
    }
}
