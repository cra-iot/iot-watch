package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.DecoderRegistry
import org.openremote.manager.iotwatch.IotWatchDecodeService
import org.openremote.manager.iotwatch.IotWatchDecodeService.CachedPlan
import org.openremote.manager.iotwatch.SameNameDecoder
import org.openremote.model.attribute.AttributeEvent
import org.openremote.model.util.ValueUtil
import org.openremote.model.watermeter.WaterMeterAsset
import spock.lang.Specification

class IotWatchDecodeServiceTest extends Specification {

    def setupSpec() { ValueUtil.initialise(null) }

    static class TestableService extends IotWatchDecodeService {
        List<AttributeEvent> dispatched = []
        @Override protected void dispatch(AttributeEvent event) { dispatched << event }
        @Override protected CachedPlan loadPlan(String assetId) { return null }
    }

    TestableService service = new TestableService()
    SameNameDecoder sameName = new SameNameDecoder()
    static final long MSG_TS = 1_700_000_000_000L
    static final long DAY = 86_400_000L

    private CachedPlan plan(String realm, String devEui, Set<String> targets, String externalId, Set<String> contested) {
        new IotWatchDecodeService.CachedPlan(realm, devEui,
            new DecoderRegistry.DecodePlan(sameName, targets), externalId, contested)
    }

    def "disjoint-field siblings each decode their own fields from a flat payload without external_id"() {
        given: "temp+humidity asset and radiation asset share a devEui; nothing contested"
        service.planCache.put("th", plan("master", "AABB", ["temperature", "humidity"] as Set, null, [] as Set))
        service.planCache.put("rad", plan("master", "AABB", ["radiation"] as Set, null, [] as Set))
        def message = [data_decoded: [temperature: 21.4d, humidity: 55, radiation: 0.12d]]

        when:
        service.onRawValue(new AttributeEvent("th", "rawValue", message, 1000L))
        service.onRawValue(new AttributeEvent("rad", "rawValue", message, 1000L))

        then:
        service.dispatched.findAll { it.ref.id == "th" }*.ref*.name as Set == ["temperature", "humidity"] as Set
        service.dispatched.findAll { it.ref.id == "rad" }*.ref*.name as Set == ["radiation"] as Set
    }

    def "two same-type meters route by external_id from a tagged payload"() {
        given:
        service.planCache.put("w1", plan("master", "AABB", ["currentReading"] as Set, "W1", ["currentReading"] as Set))
        service.planCache.put("w2", plan("master", "AABB", ["currentReading"] as Set, "W2", ["currentReading"] as Set))
        def message = [data_decoded: [readings: [
                [external_id: "W1", currentReading: 10d],
                [external_id: "W2", currentReading: 20d]]]]

        when:
        service.onRawValue(new AttributeEvent("w1", "rawValue", message, 1000L))
        service.onRawValue(new AttributeEvent("w2", "rawValue", message, 1000L))

        then:
        service.dispatched.find { it.ref.id == "w1" }.value.get() == 10d
        service.dispatched.find { it.ref.id == "w2" }.value.get() == 20d
    }

    def "contested field in an untagged payload is dropped (no duplicate fill)"() {
        given: "two same-type meters, no external_id, flat payload"
        service.planCache.put("w1", plan("master", "AABB", ["currentReading"] as Set, null, ["currentReading"] as Set))
        service.planCache.put("w2", plan("master", "AABB", ["currentReading"] as Set, null, ["currentReading"] as Set))
        def message = [data_decoded: [currentReading: 10d]]

        when:
        service.onRawValue(new AttributeEvent("w1", "rawValue", message, 1000L))
        service.onRawValue(new AttributeEvent("w2", "rawValue", message, 1000L))

        then:
        service.dispatched.isEmpty()
    }

    def "contested field in an untagged payload is dropped end-to-end with index-computed contestedNames"() {
        given: "two real WaterMeterAsset siblings sharing a devEui, no external_id"
        def w1 = new WaterMeterAsset("W1"); w1.setId("w1"); w1.setRealm("master")
        w1.getAttributes().clear()
        w1.getAttributes().getOrCreate(WaterMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR).setValue("AABB")
        w1.getAttributes().getOrCreate(WaterMeterAsset.CURRENT_READING_ATTRIBUTE_DESCRIPTOR)
        def w2 = new WaterMeterAsset("W2"); w2.setId("w2"); w2.setRealm("master")
        w2.getAttributes().clear()
        w2.getAttributes().getOrCreate(WaterMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR).setValue("AABB")
        w2.getAttributes().getOrCreate(WaterMeterAsset.CURRENT_READING_ATTRIBUTE_DESCRIPTOR)
        service.cacheAsset(w1); service.cacheAsset(w2)

        when:
        service.onRawValue(new AttributeEvent("w1", "rawValue", [data_decoded: [currentReading: 10d]], 1000L))

        then: "currentReading is contested (both siblings claim it) and the payload is untagged"
        service.dispatched.isEmpty()
    }

    def "computes contestedNames across siblings when assets are cached"() {
        given: "two water meters that both provision currentReading, same devEui"
        def a = new WaterMeterAsset("W1"); a.setId("w1"); a.setRealm("master")
        a.getAttributes().clear()
        a.getAttributes().getOrCreate(WaterMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR).setValue("AABB")
        a.getAttributes().getOrCreate(WaterMeterAsset.CURRENT_READING_ATTRIBUTE_DESCRIPTOR)
        def b = new WaterMeterAsset("W2"); b.setId("w2"); b.setRealm("master")
        b.getAttributes().clear()
        b.getAttributes().getOrCreate(WaterMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR).setValue("AABB")
        b.getAttributes().getOrCreate(WaterMeterAsset.CURRENT_READING_ATTRIBUTE_DESCRIPTOR)

        when:
        service.cacheAsset(a)
        service.cacheAsset(b)

        then: "each sees currentReading as contested (the other claims it too)"
        service.planCache.get("w1").contestedNames() == ["currentReading"] as Set
        service.planCache.get("w2").contestedNames() == ["currentReading"] as Set
    }

    def "decoded events are stamped with the reading's measured_at, not the message timestamp"() {
        given:
        service.planCache.put("w1", plan("master", "AABB", ["currentReading"] as Set, null, [] as Set))
        def message = [data_decoded: [measured_at: MSG_TS - DAY, currentReading: 12.5d]]

        when: "the message is delivered now but the measurement was taken yesterday"
        service.onRawValue(new AttributeEvent("w1", "rawValue", message, MSG_TS))

        then:
        service.dispatched.size() == 1
        service.dispatched[0].ref.name == "currentReading"
        service.dispatched[0].timestamp == MSG_TS - DAY
    }
}
