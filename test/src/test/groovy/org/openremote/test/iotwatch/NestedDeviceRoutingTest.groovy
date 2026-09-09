package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.IotWatchDecodeService
import org.openremote.manager.iotwatch.IotWatchDecodeService.CachedPlan
import org.openremote.model.attribute.AttributeEvent
import org.openremote.model.util.ValueUtil
import org.openremote.model.value.ValueType
import org.openremote.model.watermeter.WaterMeterAsset
import spock.lang.Specification

/**
 * Reproduction attempt: two assets sharing one devEui, distinguished only by external_id,
 * fed with the message shape the HTTP ingest path actually produces (data parsed by
 * ValueUtil.JSON, so JSON arrays arrive as Object[]).
 */
class NestedDeviceRoutingTest extends Specification {

    def setupSpec() { ValueUtil.initialise(null) }

    static class TestableService extends IotWatchDecodeService {
        List<AttributeEvent> dispatched = []
        List<String> warnings = []
        @Override protected void dispatch(AttributeEvent event) { dispatched << event }
        @Override protected CachedPlan loadPlan(String assetId) { return null }
    }

    TestableService service = new TestableService()
    static final long MSG_TS = 1_700_000_000_000L
    static final long HOUR = 3_600_000L

    private WaterMeterAsset meter(String id, String devEui, String externalId) {
        def asset = new WaterMeterAsset(id)
        asset.setId(id)
        asset.setRealm("master")
        asset.getAttributes().clear()
        asset.getAttributes().getOrCreate(WaterMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR).setValue(devEui)
        if (externalId != null) {
            asset.getAttributes().getOrCreate(WaterMeterAsset.EXTERNAL_ID_ATTRIBUTE_DESCRIPTOR).setValue(externalId)
        }
        asset.getAttributes().getOrCreate(WaterMeterAsset.CURRENT_READING_ATTRIBUTE_DESCRIPTOR)
        asset.getAttributes().getOrCreate(WaterMeterAsset.FLOW_RATE_ATTRIBUTE_DESCRIPTOR)
        asset.getAttributes().getOrCreate(WaterMeterAsset.BATTERY_LEVEL_ATTRIBUTE_DESCRIPTOR)
        asset
    }

    /** Parsed exactly like IngestPayload does, so arrays arrive as Object[] and not as List. */
    private static Map<String, Object> ingested(String json) {
        ValueUtil.JSON.convertValue(ValueUtil.JSON.readTree(json), ValueType.ObjectMap.class)
    }

    def "two meters sharing a devEui each decode only their own tagged reading"() {
        given: "both assets provisioned as the runbook describes"
        service.cacheAsset(meter("w1", "04B6480B50071020", "W-1"))
        service.cacheAsset(meter("w2", "04B6480B50071020", "W-2"))

        and: "the message the ingest endpoint would store in rawValue"
        def message = ingested("""
            {"EUI": "04B6480B50071020", "ts": ${MSG_TS}, "data_decoded": {"readings": [
                {"external_id": "W-1", "measured_at": ${MSG_TS - HOUR}, "currentReading": 100.0, "flow_rate": 0.5},
                {"external_id": "W-2", "measured_at": ${MSG_TS - HOUR}, "currentReading": 250.5, "flow_rate": 1.5}]}}""")

        when:
        service.onRawValue(new AttributeEvent("w1", "rawValue", message, MSG_TS))
        service.onRawValue(new AttributeEvent("w2", "rawValue", message, MSG_TS))

        then: "selectors were picked up from the provisioned external_id attribute"
        service.planCache.get("w1").externalId() == "W-1"
        service.planCache.get("w2").externalId() == "W-2"

        and: "each asset wrote its own reading only"
        service.dispatched.findAll { it.ref.id == "w1" && it.ref.name == "currentReading" }*.value*.get() == [100.0d]
        service.dispatched.findAll { it.ref.id == "w2" && it.ref.name == "currentReading" }*.value*.get() == [250.5d]
        service.dispatched.every { it.timestamp == MSG_TS - HOUR }
    }

    def "tagged readings without measured_at still route to their own meter"() {
        given:
        service.cacheAsset(meter("w1", "AABB", "W-1"))
        service.cacheAsset(meter("w2", "AABB", "W-2"))
        def message = ingested("""
            {"EUI": "AABB", "ts": ${MSG_TS}, "data_decoded": {"readings": [
                {"external_id": "W-1", "currentReading": 100.0},
                {"external_id": "W-2", "currentReading": 250.5}]}}""")

        when:
        service.onRawValue(new AttributeEvent("w1", "rawValue", message, MSG_TS))
        service.onRawValue(new AttributeEvent("w2", "rawValue", message, MSG_TS))

        then:
        service.dispatched.findAll { it.ref.id == "w1" }*.value*.get() == [100.0d]
        service.dispatched.findAll { it.ref.id == "w2" }*.value*.get() == [250.5d]
    }

    def "a tagged reading no asset claims is dropped without any warning"() {
        given: "the platform tags with the meter serial, the operator typed something else"
        service.cacheAsset(meter("w1", "AABB", "W-1"))
        service.cacheAsset(meter("w2", "AABB", "W-2"))
        def message = ingested("""
            {"EUI": "AABB", "ts": ${MSG_TS}, "data_decoded": {"readings": [
                {"external_id": "1111111111111111", "currentReading": 100.0},
                {"external_id": "2222222222222222", "currentReading": 250.5}]}}""")

        when:
        service.onRawValue(new AttributeEvent("w1", "rawValue", message, MSG_TS))
        service.onRawValue(new AttributeEvent("w2", "rawValue", message, MSG_TS))

        then: "nothing is written and nothing is reported"
        service.dispatched.isEmpty()
    }

    def "external_id provisioned but left empty consumes nothing"() {
        given:
        service.cacheAsset(meter("w1", "AABB", ""))
        service.cacheAsset(meter("w2", "AABB", "W-2"))
        def message = ingested("""
            {"EUI": "AABB", "ts": ${MSG_TS}, "data_decoded": {"readings": [
                {"external_id": "W-1", "currentReading": 100.0},
                {"external_id": "W-2", "currentReading": 250.5}]}}""")

        when:
        service.onRawValue(new AttributeEvent("w1", "rawValue", message, MSG_TS))
        service.onRawValue(new AttributeEvent("w2", "rawValue", message, MSG_TS))

        then:
        service.planCache.get("w1").externalId() == ""
        service.dispatched.findAll { it.ref.id == "w1" }.isEmpty()
        service.dispatched.findAll { it.ref.id == "w2" }*.value*.get() == [250.5d]
    }

    def "device-level fields sitting next to the readings array are silently dropped"() {
        given: "one meter behind the device, so nothing is contested"
        service.cacheAsset(meter("w1", "AABB", "W-1"))
        and: "the gateway reports its own battery next to the per-meter readings"
        def message = ingested("""
            {"EUI": "AABB", "ts": ${MSG_TS}, "data_decoded": {
                "battery_level": 90,
                "readings": [{"external_id": "W-1", "currentReading": 100.0}]}}""")

        when:
        service.onRawValue(new AttributeEvent("w1", "rawValue", message, MSG_TS))

        then: "the reading is written"
        service.dispatched.findAll { it.ref.name == "currentReading" }*.value*.get() == [100.0d]

        and: "but battery_level, provisioned and declared, never reaches the asset"
        service.dispatched.findAll { it.ref.name == "battery_level" }.isEmpty()
    }
}
