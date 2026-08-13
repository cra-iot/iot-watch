package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.ReadingRouter
import spock.lang.Specification

class ReadingRouterTest extends Specification {

    def "flat untagged payload yields one routed reading minus contested fields"() {
        given:
        def message = [data_decoded: [temperature: 21.4d, humidity: 55, battery_level: 90]]

        when:
        def routed = ReadingRouter.route(message, null,
                ["temperature", "humidity", "battery_level"] as Set, ["battery_level"] as Set)

        then:
        routed.size() == 1
        routed[0].message().get("data_decoded") == [temperature: 21.4d, humidity: 55, battery_level: 90]
        routed[0].effectiveTargets() == ["temperature", "humidity"] as Set   // battery_level contested → dropped
    }

    def "tagged readings route the matching one with full targets"() {
        given:
        def message = [data_decoded: [readings: [
                [external_id: "W1", volume: 12.3d],
                [external_id: "W2", volume: 45.6d]]]]

        when:
        def routed = ReadingRouter.route(message, "W2", ["volume"] as Set, ["volume"] as Set)

        then:
        routed.size() == 1
        routed[0].message().get("data_decoded") == [external_id: "W2", volume: 45.6d]
        routed[0].effectiveTargets() == ["volume"] as Set   // tagged → contested still allowed
    }

    def "an asset with no selector ignores tagged readings"() {
        given:
        def message = [data_decoded: [readings: [[external_id: "W1", volume: 12.3d]]]]

        expect:
        ReadingRouter.route(message, null, ["volume"] as Set, [] as Set).isEmpty()
    }

    def "duplicate external_id in the payload consumes nothing"() {
        given:
        def message = [data_decoded: [readings: [
                [external_id: "W1", volume: 1d],
                [external_id: "W1", volume: 2d]]]]

        expect:
        ReadingRouter.route(message, "W1", ["volume"] as Set, [] as Set).isEmpty()
    }

    def "returns empty when data_decoded is absent or not a map"() {
        expect:
        ReadingRouter.route(message as Map, null, ["x"] as Set, [] as Set).isEmpty()

        where:
        message << [[:], [data_decoded: "nope"]]
    }
}
