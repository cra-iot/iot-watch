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

    def "writes nothing when the platform signalled a failed decode"() {
        expect:
        decoder.decode("asset1", message as Map, ["currentReading", "leakage"] as Set, 1000L).isEmpty()

        where:
        message << [
            [data_decoded: null],
            [data_decoded: [decoded: false, ok: false]],
            [data_decoded: [decoded: false]],
            [data_decoded: [ok: false]],
            [data_decoded: [decoded: false, currentReading: 123.4d]],   // real key present but decode failed
            [data_decoded: [ok: false, currentReading: 123.4d]]
        ]
    }

    def "decodes normally when success flags are present"() {
        given:
        def message = [data_decoded: [decoded: true, ok: true, currentReading: 123.4d]]

        when:
        def events = decoder.decode("asset1", message, ["currentReading"] as Set, 1000L)

        then:
        events*.ref*.name == ["currentReading"]
        events[0].value.get() == 123.4d
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
        !names.contains("external_id")   // routing/binding key, never decoded
        names.contains("meter_id")       // still a decodable field
    }

    def "a reserved key is never written as an attribute value, even if offered as a target"() {
        when: "measured_at and external_id are (wrongly) among the targets"
        def events = new SameNameDecoder().decode("a1",
                [data_decoded: [measured_at: 1_700_000_000_000L, external_id: "W1", currentReading: 5d]],
                ["currentReading", "measured_at", "external_id"] as Set, 1_000L)

        then: "only the real measurement is emitted"
        events*.ref*.name == ["currentReading"]
    }

    def "the container and success-flag keys are excluded too, not merely unmatched by chance"() {
        when: "readings, decoded and ok are all (wrongly) among the targets"
        def events = new SameNameDecoder().decode("a1",
                [data_decoded: [decoded: true, ok: true, readings: [[currentReading: 1d]], currentReading: 5d]],
                ["currentReading", "readings", "decoded", "ok"] as Set, 1_000L)

        then: "only the real measurement is emitted — the exclusion is enforced, not accidental"
        events*.ref*.name == ["currentReading"]
    }
}
