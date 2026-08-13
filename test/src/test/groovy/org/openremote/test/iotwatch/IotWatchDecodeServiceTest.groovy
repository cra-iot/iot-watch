package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.DecoderRegistry
import org.openremote.manager.iotwatch.GnssDecoder
import org.openremote.manager.iotwatch.IotWatchDecodeService
import org.openremote.model.attribute.AttributeEvent
import spock.lang.Specification

class IotWatchDecodeServiceTest extends Specification {

    static class TestableService extends IotWatchDecodeService {
        List<AttributeEvent> dispatched = []

        @Override
        protected void dispatch(AttributeEvent event) {
            dispatched << event
        }

        @Override
        protected DecoderRegistry.DecodePlan loadPlan(String assetId) {
            return null   // no DB in unit tests; tests pre-populate planCache
        }
    }

    TestableService service = new TestableService()

    def "decodes a rawValue event using the cached plan"() {
        given:
        service.planCache.put("t1",
            new DecoderRegistry.DecodePlan(new GnssDecoder(), ["location", "battery"] as Set))
        def message = [data_decoded: [gnss_latitude: 50.1d, gnss_longitude: 14.4d, battery_pct: 87]]

        when:
        service.onRawValue(new AttributeEvent("t1", "rawValue", message, 3000L))

        then:
        service.dispatched*.ref*.name as Set == ["location", "battery"] as Set
        service.dispatched.every { it.ref.id == "t1" && it.timestamp == 3000L }
    }

    def "ignores an event whose asset has no cached plan"() {
        when:
        service.onRawValue(new AttributeEvent("unknown", "rawValue", [data_decoded: [battery_pct: 1]], 1L))

        then:
        service.dispatched.isEmpty()
    }

    def "ignores an event whose value is not a map"() {
        given:
        service.planCache.put("t1",
            new DecoderRegistry.DecodePlan(new GnssDecoder(), ["battery"] as Set))

        when:
        service.onRawValue(new AttributeEvent("t1", "rawValue", "not-a-map", 1L))

        then:
        service.dispatched.isEmpty()
    }
}
