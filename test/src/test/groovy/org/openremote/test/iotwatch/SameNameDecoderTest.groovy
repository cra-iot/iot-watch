package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.SameNameDecoder
import org.openremote.model.util.ValueUtil
import org.openremote.model.watermeter.WaterMeterAsset
import spock.lang.Specification

class SameNameDecoderTest extends Specification {

    // Constructing a real Asset (WaterMeterAsset) requires the asset model to be
    // initialised (Asset(String) -> ValueUtil.initialiseAssetAttributes); this test module
    // never spins up a container, so trigger the same one-time, container-less
    // initialisation the manager normally does at startup.
    def setupSpec() {
        ValueUtil.initialise(null)
    }

    SameNameDecoder decoder = new SameNameDecoder()

    def "copies only decoded keys that are in targetNames"() {
        given:
        def message = [data_decoded: [
            currentReading: 123.4d,
            leakage       : true,
            meter_id      : "M-1",   // modelled but not in targetNames
            unknown_key   : 9,        // not modelled
            notes         : "hello"   // base-Asset attribute, never a candidate
        ]]
        def targetNames = ["currentReading", "leakage"] as Set

        when:
        def events = decoder.decode("asset1", message, targetNames, 1000L)

        then:
        events*.ref*.name as Set == ["currentReading", "leakage"] as Set
        def byName = events.collectEntries { [it.ref.name, it.value.get()] }
        byName["currentReading"] == 123.4d
        byName["leakage"] == true
        events.every { it.ref.id == "asset1" && it.timestamp == 1000L }
    }

    def "returns nothing when data_decoded is missing or not a map"() {
        expect:
        decoder.decode("asset1", message as Map, ["currentReading"] as Set, 1000L).isEmpty()

        where:
        message << [ [:], [data_decoded: "not-a-map"], [other: 1] ]
    }

    def "candidateAttributeNames are the class's own attributes minus devEui and rawValue"() {
        when:
        def names = decoder.candidateAttributeNames(new WaterMeterAsset("W"))

        then:
        names.contains("currentReading")
        names.contains("leakage")
        !names.contains("devEui")
        !names.contains("rawValue")
        !names.contains("notes")   // inherited base attribute, not declared on WaterMeterAsset
    }
}
