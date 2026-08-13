package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.DecoderRegistry
import org.openremote.manager.iotwatch.GnssDecoder
import org.openremote.manager.iotwatch.SameNameDecoder
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.attribute.Attribute
import org.openremote.model.tracker.TrackerAsset
import org.openremote.model.util.ValueUtil
import org.openremote.model.watermeter.WaterMeterAsset
import spock.lang.Specification

class DecoderRegistryTest extends Specification {

    // Constructing a real Asset (WaterMeterAsset/TrackerAsset/ThingAsset) requires the asset
    // model to be initialised (Asset(String) -> ValueUtil.initialiseAssetAttributes); this test
    // module never spins up a container, so trigger the same one-time, container-less
    // initialisation the manager normally does at startup.
    def setupSpec() {
        ValueUtil.initialise(null)
    }

    DecoderRegistry registry = new DecoderRegistry()

    def "builds a same-name plan intersecting candidates with provisioned attributes"() {
        given:
        def asset = new WaterMeterAsset("W")
        asset.getAttributes().clear()   // control the provisioned set exactly
        asset.getAttributes().getOrCreate(WaterMeterAsset.CURRENT_READING_ATTRIBUTE_DESCRIPTOR)
        asset.getAttributes().getOrCreate(WaterMeterAsset.LEAKAGE_ATTRIBUTE_DESCRIPTOR)

        when:
        def plan = registry.planFor(asset)

        then:
        plan.isPresent()
        plan.get().decoder() instanceof SameNameDecoder
        plan.get().targetNames() == ["currentReading", "leakage"] as Set
    }

    def "builds a gnss plan for trackers, gated by provisioned attributes"() {
        given:
        def asset = new TrackerAsset("T")
        asset.getAttributes().clear()
        asset.getAttributes().getOrCreate(TrackerAsset.BATTERY_ATTRIBUTE_DESCRIPTOR)

        when:
        def plan = registry.planFor(asset)

        then:
        plan.isPresent()
        plan.get().decoder() instanceof GnssDecoder
        plan.get().targetNames() == ["battery"] as Set   // location not provisioned here
    }

    def "returns empty for an unregistered asset type"() {
        expect:
        registry.planFor(new ThingAsset("C")).isEmpty()
    }

    def "returns empty when no candidate attribute is provisioned"() {
        given:
        def asset = new WaterMeterAsset("W")
        asset.getAttributes().clear()   // no measurement attributes at all

        expect:
        registry.planFor(asset).isEmpty()
    }
}
