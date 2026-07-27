package org.openremote.test.iotwatch

import jakarta.ws.rs.core.Response
import org.openremote.manager.iotwatch.IngestKeyStore
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
        AttributeEvent dispatched
        long serverTime = 42L

        @Override
        protected Set<String> queryAssetIdsByDevEui(String realm, String eui) {
            queryCount++
            return queryResult
        }

        @Override
        protected void dispatch(AttributeEvent event) {
            dispatched = event
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
        service.dispatched == null
    }

    def "returns 409 when multiple assets match"() {
        given:
        service.cache.put("master", "00112233AABBCCDD", "asset1")
        service.cache.put("master", "00112233AABBCCDD", "asset2")

        expect:
        service.handle("master", "good-key", ENVELOPE).status == 409
        service.dispatched == null
    }

    def "writes rawValue with device timestamp on cache hit"() {
        given:
        service.cache.put("master", "00112233AABBCCDD", "asset1")

        when:
        def response = service.handle("master", "good-key", ENVELOPE)

        then:
        response.status == 200
        service.dispatched.ref.id == "asset1"
        service.dispatched.ref.name == "rawValue"
        service.dispatched.timestamp == 1773397633008L
        (service.dispatched.value.get() as Map).get("bat") == 254
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
        service.dispatched.ref.id == "asset9"
    }

    def "uses server time when the message has no ts"() {
        given:
        service.cache.put("master", "00112233AABBCCDD", "asset1")

        when:
        service.handle("master", "good-key", new IngestEnvelope("D", '{"EUI":"00112233AABBCCDD"}', "L", []))

        then:
        service.dispatched.timestamp == 42L
    }
}
