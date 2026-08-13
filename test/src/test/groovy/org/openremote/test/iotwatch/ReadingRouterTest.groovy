package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.ReadingRouter
import spock.lang.Specification

class ReadingRouterTest extends Specification {

    def "flat untagged payload yields one routed reading minus contested fields"() {
        given:
        def message = [data_decoded: [temperature: 21.4d, humidity: 55, battery_level: 90]]

        when:
        def result = ReadingRouter.route(message, null,
                ["temperature", "humidity", "battery_level"] as Set, ["battery_level"] as Set)
        def routed = result.routed()

        then:
        routed.size() == 1
        routed[0].message().get("data_decoded") == [temperature: 21.4d, humidity: 55, battery_level: 90]
        routed[0].effectiveTargets() == ["temperature", "humidity"] as Set   // battery_level contested → dropped
        result.warnings().size() == 1
        result.warnings()[0].contains("battery_level")
    }

    def "disjoint untagged reading with no contested field present yields no warnings"() {
        given: "battery_level is contested elsewhere, but this reading doesn't carry it"
        def message = [data_decoded: [temperature: 21.4d, humidity: 55]]

        when:
        def result = ReadingRouter.route(message, null,
                ["temperature", "humidity"] as Set, ["battery_level"] as Set)

        then:
        result.routed().size() == 1
        result.routed()[0].effectiveTargets() == ["temperature", "humidity"] as Set
        result.warnings().isEmpty()
    }

    def "tagged readings route the matching one with full targets"() {
        given:
        def message = [data_decoded: [readings: [
                [external_id: "W1", volume: 12.3d],
                [external_id: "W2", volume: 45.6d]]]]

        when:
        def result = ReadingRouter.route(message, "W2", ["volume"] as Set, ["volume"] as Set)
        def routed = result.routed()

        then:
        routed.size() == 1
        routed[0].message().get("data_decoded") == [external_id: "W2", volume: 45.6d]
        routed[0].effectiveTargets() == ["volume"] as Set   // tagged → contested still allowed
        result.warnings().isEmpty()
    }

    def "an asset with no selector ignores tagged readings"() {
        given:
        def message = [data_decoded: [readings: [[external_id: "W1", volume: 12.3d]]]]

        expect:
        ReadingRouter.route(message, null, ["volume"] as Set, [] as Set).routed().isEmpty()
    }

    def "duplicate external_id in the payload consumes nothing and warns"() {
        given:
        def message = [data_decoded: [readings: [
                [external_id: "W1", volume: 1d],
                [external_id: "W1", volume: 2d]]]]

        when:
        def result = ReadingRouter.route(message, "W1", ["volume"] as Set, [] as Set)

        then:
        result.routed().isEmpty()
        result.warnings().size() == 1
        result.warnings()[0].contains("W1")
    }

    def "single untagged reading with a contested field is dropped and warns"() {
        given:
        def message = [data_decoded: [currentReading: 10d]]

        when:
        def result = ReadingRouter.route(message, null, ["currentReading"] as Set, ["currentReading"] as Set)

        then:
        result.routed().size() == 1
        result.routed()[0].effectiveTargets().isEmpty()   // currentReading contested → dropped
        result.warnings().size() == 1
        result.warnings()[0].contains("currentReading")
    }

    def "returns empty when data_decoded is absent or not a map"() {
        expect:
        ReadingRouter.route(message as Map, null, ["x"] as Set, [] as Set).routed().isEmpty()

        where:
        message << [[:], [data_decoded: "nope"]]
    }
}
