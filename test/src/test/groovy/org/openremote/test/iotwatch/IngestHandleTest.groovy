package org.openremote.test.iotwatch

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.ws.rs.core.Response
import org.openremote.manager.iotwatch.IngestKeyStore
import org.openremote.manager.iotwatch.IngestMetrics
import org.openremote.manager.iotwatch.IotWatchIngestService
import org.openremote.model.attribute.AttributeEvent
import org.openremote.model.iotwatch.IngestEnvelope
import spock.lang.Specification

class IngestHandleTest extends Specification {

    static final String INNER =
        '{"cmd":"gw","ts":1773397633008,"data":"cbe006e001c10106aa7fff","bat":254,"EUI":"00112233AABBCCDD"}'
    static final IngestEnvelope ENVELOPE = new IngestEnvelope("D", INNER, "L", [])

    static class TestableService extends IotWatchIngestService {
        Set<String> queryResult = [] as Set
        int queryCount = 0
        List<AttributeEvent> dispatched = []
        long serverTime = 42L

        @Override
        protected Set<String> queryAssetIdsByDevEui(String realm, String eui) {
            queryCount++
            return queryResult
        }

        @Override
        protected void dispatch(AttributeEvent event) {
            dispatched << event
        }

        @Override
        protected long currentTimeMillis() {
            return serverTime
        }
    }

    TestableService service = new TestableService()

    def setup() {
        service.keyStore = IngestKeyStore.parse("master:good-key")
    }

    def "rejects wrong or missing key and unknown realm with 401"() {
        expect:
        service.handle(realm, key, ENVELOPE).status == 401

        where:
        realm      | key
        "master"   | "bad-key"
        "master"   | null
        "unknown"  | "good-key"
        null       | "good-key"
    }

    def "rejects unparseable payload with 400"() {
        expect:
        service.handle("master", "good-key", new IngestEnvelope("D", "not json", "L", [])).status == 400
    }

    def "returns 404 when no asset matches"() {
        expect:
        service.handle("master", "good-key", ENVELOPE).status == 404
        service.dispatched.isEmpty()
    }

    def "fans out rawValue to every asset that shares the devEui"() {
        given:
        service.cache.put("master", "00112233AABBCCDD", "asset1")
        service.cache.put("master", "00112233AABBCCDD", "asset2")

        when:
        def response = service.handle("master", "good-key", ENVELOPE)

        then:
        response.status == 200
        service.dispatched*.ref*.id as Set == ["asset1", "asset2"] as Set
        service.dispatched.every { it.ref.name == "rawValue" && it.timestamp == 1773397633008L }
    }

    def "writes rawValue with device timestamp on cache hit"() {
        given:
        service.cache.put("master", "00112233AABBCCDD", "asset1")

        when:
        def response = service.handle("master", "good-key", ENVELOPE)

        then:
        response.status == 200
        service.dispatched[0].ref.id == "asset1"
        service.dispatched[0].ref.name == "rawValue"
        service.dispatched[0].timestamp == 1773397633008L
        (service.dispatched[0].value.get() as Map).get("bat") == 254
        service.queryCount == 0
    }

    def "falls back to a DB query on cache miss and populates the cache"() {
        given:
        service.queryResult = ["asset9"] as Set

        when:
        def first = service.handle("master", "good-key", ENVELOPE)
        def second = service.handle("master", "good-key", ENVELOPE)

        then:
        first.status == 200
        second.status == 200
        service.queryCount == 1   // second request served from cache
        service.dispatched[0].ref.id == "asset9"
    }

    def "caches every id returned by the fallback query"() {
        given:
        service.queryResult = ["asset8", "asset9"] as Set

        when:
        service.handle("master", "good-key", ENVELOPE)   // fallback populates cache
        service.handle("master", "good-key", ENVELOPE)   // served from cache

        then:
        service.queryCount == 1
        service.cache.resolve("master", "00112233AABBCCDD") == ["asset8", "asset9"] as Set
    }

    def "uses server time when the message has no ts"() {
        given:
        service.cache.put("master", "00112233AABBCCDD", "asset1")

        when:
        service.handle("master", "good-key", new IngestEnvelope("D", '{"EUI":"00112233AABBCCDD"}', "L", []))

        then:
        service.dispatched[0].timestamp == 42L
    }

    def "counts every request outcome per realm"() {
        given:
        def registry = new SimpleMeterRegistry()
        service.metrics = IngestMetrics.create(registry)
        service.cache.put("master", "00112233AABBCCDD", "asset1")

        when:
        service.handle("master", "good-key", ENVELOPE)                                        // accepted
        service.handle("master", "bad-key", ENVELOPE)                                         // unauthorized, known realm
        service.handle("nobody", "good-key", ENVELOPE)                                        // unauthorized, unknown realm
        service.handle("master", "good-key", new IngestEnvelope("D", "not json", "L", []))    // bad_request

        then:
        def requests = { String realm, String outcome ->
            registry.get(IngestMetrics.REQUESTS_METER_NAME).tags("realm", realm, "outcome", outcome).counter().count()
        }
        requests("master", IngestMetrics.OUTCOME_ACCEPTED) == 1.0d
        requests("master", IngestMetrics.OUTCOME_UNAUTHORIZED) == 1.0d
        requests(IngestMetrics.UNKNOWN_REALM, IngestMetrics.OUTCOME_UNAUTHORIZED) == 1.0d
        requests("master", IngestMetrics.OUTCOME_BAD_REQUEST) == 1.0d
    }

    def "counts unknown device and cache fallback"() {
        given:
        def registry = new SimpleMeterRegistry()
        service.metrics = IngestMetrics.create(registry)

        when: "cache and DB both miss"
        service.handle("master", "good-key", ENVELOPE)

        and: "DB fallback hits and populates the cache"
        service.queryResult = ["asset9"] as Set
        service.handle("master", "good-key", ENVELOPE)
        service.handle("master", "good-key", ENVELOPE)

        then:
        registry.get(IngestMetrics.REQUESTS_METER_NAME)
            .tags("realm", "master", "outcome", IngestMetrics.OUTCOME_UNKNOWN_DEVICE).counter().count() == 1.0d
        registry.get(IngestMetrics.REQUESTS_METER_NAME)
            .tags("realm", "master", "outcome", IngestMetrics.OUTCOME_ACCEPTED).counter().count() == 2.0d
        // two fallbacks: the initial full miss and the populating hit; the third request is a cache hit
        registry.get(IngestMetrics.FALLBACK_METER_NAME).tags("realm", "master").counter().count() == 2.0d
    }
}
