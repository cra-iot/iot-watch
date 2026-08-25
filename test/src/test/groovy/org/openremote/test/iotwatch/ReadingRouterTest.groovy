package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.ReadingRouter
import org.openremote.model.util.ValueUtil
import org.openremote.model.value.ValueType
import spock.lang.Specification

class ReadingRouterTest extends Specification {

    // A fixed, realistic message timestamp (2023-11-14T22:13:20Z) so the validity window is deterministic.
    static final long MSG_TS = 1_700_000_000_000L
    static final long HOUR = 3_600_000L
    static final long DAY = 86_400_000L

    // ValueUtil.JSON is used below to build a message the way the ingest path builds it; the
    // mapper is only configured once ValueUtil has been initialised (see SameNameDecoderTest).
    def setupSpec() {
        ValueUtil.initialise(null)
    }

    /**
     * Builds a message exactly as IngestPayload.parse does — JSON text through ValueUtil.JSON
     * into a ValueType.ObjectMap. This is the representation the HTTP ingest path produces, and
     * it differs from a Groovy map literal: ValueUtil enables USE_JAVA_ARRAY_FOR_JSON_ARRAY, so
     * an untyped JSON array deserialises to Object[] rather than to a List.
     */
    static Map<String, Object> ingested(String json) {
        return ValueUtil.JSON.convertValue(ValueUtil.JSON.readTree(json), ValueType.ObjectMap.class)
    }

    def "flat untagged payload yields one routed reading minus contested fields"() {
        given:
        def message = [data_decoded: [temperature: 21.4d, humidity: 55, battery_level: 90]]

        when:
        def result = ReadingRouter.route(message, null,
                ["temperature", "humidity", "battery_level"] as Set, ["battery_level"] as Set, MSG_TS)
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
                ["temperature", "humidity"] as Set, ["battery_level"] as Set, MSG_TS)

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
        def result = ReadingRouter.route(message, "W2", ["volume"] as Set, ["volume"] as Set, MSG_TS)
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
        ReadingRouter.route(message, null, ["volume"] as Set, [] as Set, MSG_TS).routed().isEmpty()
    }

    def "duplicate external_id in the payload consumes nothing and warns"() {
        given:
        def message = [data_decoded: [readings: [
                [external_id: "W1", volume: 1d],
                [external_id: "W1", volume: 2d]]]]

        when:
        def result = ReadingRouter.route(message, "W1", ["volume"] as Set, [] as Set, MSG_TS)

        then:
        result.routed().isEmpty()
        result.warnings().size() == 1
        result.warnings()[0].contains("W1")
    }

    def "single untagged reading with a contested field is dropped and warns"() {
        given:
        def message = [data_decoded: [currentReading: 10d]]

        when:
        def result = ReadingRouter.route(message, null, ["currentReading"] as Set, ["currentReading"] as Set, MSG_TS)

        then:
        result.routed().size() == 1
        result.routed()[0].effectiveTargets().isEmpty()   // currentReading contested → dropped
        result.warnings().size() == 1
        result.warnings()[0].contains("currentReading")
    }

    def "returns empty when data_decoded is absent or not a map"() {
        expect:
        ReadingRouter.route(message as Map, null, ["x"] as Set, [] as Set, MSG_TS).routed().isEmpty()

        where:
        message << [[:], [data_decoded: "nope"]]
    }

    def "a flat reading is stored under its own measured_at"() {
        given:
        def message = [data_decoded: [measured_at: MSG_TS - DAY, currentReading: 12.5d]]

        when:
        def result = ReadingRouter.route(message, null, ["currentReading"] as Set, [] as Set, MSG_TS)

        then:
        result.routed().size() == 1
        result.routed()[0].timestamp() == MSG_TS - DAY
        result.warnings().isEmpty()
    }

    def "a reading without measured_at falls back to the message timestamp"() {
        given:
        def message = [data_decoded: [currentReading: 12.5d]]

        when:
        def result = ReadingRouter.route(message, null, ["currentReading"] as Set, [] as Set, MSG_TS)

        then:
        result.routed()[0].timestamp() == MSG_TS
        result.warnings().isEmpty()
    }

    def "a batch-level measured_at applies to readings that carry none"() {
        given:
        def message = [data_decoded: [measured_at: MSG_TS - HOUR, readings: [
                [temperature: 21.4d], [radiation: 0.12d]]]]

        when:
        def result = ReadingRouter.route(message, null,
                ["temperature", "radiation"] as Set, [] as Set, MSG_TS)

        then:
        result.routed().size() == 2
        result.routed()*.timestamp() == [MSG_TS - HOUR, MSG_TS - HOUR]
        result.warnings().isEmpty()
    }

    def "a reading's own measured_at wins over the batch-level one"() {
        given:
        def message = [data_decoded: [measured_at: MSG_TS - HOUR, readings: [
                [temperature: 21.4d, measured_at: MSG_TS - 2 * HOUR], [radiation: 0.12d]]]]

        when:
        def result = ReadingRouter.route(message, null,
                ["temperature", "radiation"] as Set, [] as Set, MSG_TS)

        then:
        result.routed()*.timestamp() == [MSG_TS - 2 * HOUR, MSG_TS - HOUR]
        result.warnings().isEmpty()
    }

    def "an implausible measured_at is rejected, falls back and warns"() {
        given:
        def message = [data_decoded: [measured_at: badValue, currentReading: 1d]]

        when:
        def result = ReadingRouter.route(message, null, ["currentReading"] as Set, [] as Set, MSG_TS)

        then: "the reading is kept, but filed under the message timestamp"
        result.routed().size() == 1
        result.routed()[0].timestamp() == MSG_TS
        result.warnings().size() == 1
        result.warnings()[0].contains("measured_at")

        where: "seconds instead of millis, too old, too far in the future, not a number, not integral"
        badValue << [MSG_TS.intdiv(1000), MSG_TS - 400 * DAY, MSG_TS + 2 * DAY, "1700000000000", 1.5d]
    }

    def "a measured_at at the edges of the window is accepted"() {
        given:
        def message = [data_decoded: [measured_at: edge, currentReading: 1d]]

        when:
        def result = ReadingRouter.route(message, null, ["currentReading"] as Set, [] as Set, MSG_TS)

        then:
        result.routed()[0].timestamp() == edge
        result.warnings().isEmpty()

        where:
        edge << [MSG_TS - ReadingRouter.MAX_BACKDATE_MILLIS, MSG_TS + ReadingRouter.MAX_FUTURE_SKEW_MILLIS]
    }

    def "several readings tagged for our meter at distinct times are all routed"() {
        given: "a buffered history batch for one meter"
        def message = [data_decoded: [readings: [
                [external_id: "W1", measured_at: MSG_TS - 2 * HOUR, currentReading: 10d],
                [external_id: "W1", measured_at: MSG_TS - HOUR, currentReading: 11d],
                [external_id: "W2", measured_at: MSG_TS - HOUR, currentReading: 99d]]]]

        when:
        def result = ReadingRouter.route(message, "W1",
                ["currentReading"] as Set, ["currentReading"] as Set, MSG_TS)

        then: "only our meter's readings, one per measurement time, with full targets"
        result.routed().size() == 2
        result.routed()*.timestamp() == [MSG_TS - 2 * HOUR, MSG_TS - HOUR]
        result.routed()*.effectiveTargets().every { it == ["currentReading"] as Set }
        result.warnings().isEmpty()
    }

    def "tagged readings that share a measurement time are still refused"() {
        given: "two readings for our meter at the same time — one would overwrite the other"
        def message = [data_decoded: [readings: [
                [external_id: "W1", measured_at: MSG_TS - HOUR, currentReading: 10d],
                [external_id: "W1", measured_at: MSG_TS - HOUR, currentReading: 11d]]]]

        when:
        def result = ReadingRouter.route(message, "W1", ["currentReading"] as Set, [] as Set, MSG_TS)

        then:
        result.routed().isEmpty()
        result.warnings().size() == 1
        result.warnings()[0].contains("W1")
    }

    def "untagged readings that would overwrite each other are reported"() {
        given: "two untagged readings for the same field with no measurement time"
        def message = [data_decoded: [readings: [[currentReading: 10d], [currentReading: 11d]]]]

        when:
        def result = ReadingRouter.route(message, null, ["currentReading"] as Set, [] as Set, MSG_TS)

        then: "both are still routed (the last write wins) but the loss is reported"
        result.routed().size() == 2
        result.warnings().size() == 1
        result.warnings()[0].contains("currentReading")
        result.warnings()[0].contains("collide")
    }

    def "untagged readings carrying different fields at the same time do not warn"() {
        given:
        def message = [data_decoded: [readings: [[temperature: 21.4d], [radiation: 0.12d]]]]

        when:
        def result = ReadingRouter.route(message, null,
                ["temperature", "radiation"] as Set, [] as Set, MSG_TS)

        then:
        result.routed().size() == 2
        result.warnings().isEmpty()
    }

    def "readings built the way the ingest path builds them are routed"() {
        given: "a message deserialised by ValueUtil.JSON, as IngestPayload.parse produces it"
        def message = ingested("""
            {"EUI": "00112233AABBCCDD", "ts": ${MSG_TS},
             "data_decoded": {"readings": [
                 {"measured_at": ${MSG_TS - HOUR}, "currentReading": 10.0},
                 {"measured_at": ${MSG_TS}, "currentReading": 11.0}]}}""")

        expect: "the readings list is an Object[], which is what production actually carries"
        message.data_decoded.readings instanceof Object[]

        when:
        def result = ReadingRouter.route(message, null, ["currentReading"] as Set, [] as Set, MSG_TS)

        then: "both readings are routed, each under its own measurement time"
        result.routed().size() == 2
        result.routed()*.timestamp() == [MSG_TS - HOUR, MSG_TS]
        result.routed()*.message()*.get("data_decoded")*.get("currentReading") == [10.0d, 11.0d]
        result.warnings().isEmpty()
    }

    def "a tagged history batch arriving as an Object[] is routed"() {
        given: "the same array representation, built directly"
        def message = [data_decoded: [readings: [
                [external_id: "W1", measured_at: MSG_TS - 2 * HOUR, currentReading: 10d],
                [external_id: "W1", measured_at: MSG_TS - HOUR, currentReading: 11d]] as Object[]]]

        when:
        def result = ReadingRouter.route(message, "W1",
                ["currentReading"] as Set, ["currentReading"] as Set, MSG_TS)

        then:
        result.routed().size() == 2
        result.routed()*.timestamp() == [MSG_TS - 2 * HOUR, MSG_TS - HOUR]
        result.warnings().isEmpty()
    }

    def "three readings colliding on one field yield exactly one warning"() {
        given: "three untagged readings for the same field with no measurement time"
        def message = [data_decoded: [readings: [
                [currentReading: 10d], [currentReading: 11d], [currentReading: 12d]]]]

        when:
        def result = ReadingRouter.route(message, null, ["currentReading"] as Set, [] as Set, MSG_TS)

        then: "one warning per (timestamp, colliding field set), not one per colliding pair"
        result.routed().size() == 3
        result.warnings().size() == 1
        result.warnings()[0].contains("currentReading")
        result.warnings()[0].contains(String.valueOf(MSG_TS))
    }

    def "a non-finite measured_at is rejected and reported as sent"() {
        given: "Math.floor(Infinity) == Infinity, so an integral-only check lets it through"
        def message = [data_decoded: [measured_at: Double.POSITIVE_INFINITY, currentReading: 1d]]

        when:
        def result = ReadingRouter.route(message, null, ["currentReading"] as Set, [] as Set, MSG_TS)

        then: "the warning names the value the decoder sent, not Long.MAX_VALUE"
        result.routed().size() == 1
        result.routed()[0].timestamp() == MSG_TS
        result.warnings().size() == 1
        result.warnings()[0].contains("Infinity")
        !result.warnings()[0].contains(String.valueOf(Long.MAX_VALUE))
    }

    def "a measured_at above 2^63 is rejected rather than wrapped into the window"() {
        given: "2^64 + MSG_TS, whose low 64 bits are exactly MSG_TS"
        def message = ingested('{"data_decoded": {"measured_at": 18446745773709551616, "currentReading": 1.0}}')

        expect: "JSON of that magnitude deserialises to BigInteger, whose longValue() truncates"
        message.data_decoded.measured_at instanceof BigInteger
        ((BigInteger) message.data_decoded.measured_at).longValue() == MSG_TS

        when:
        def result = ReadingRouter.route(message, null, ["currentReading"] as Set, [] as Set, MSG_TS)

        then: "rejected on magnitude, not silently accepted as an in-window timestamp"
        result.warnings().size() == 1
        result.warnings()[0].contains("measured_at")
        result.routed()[0].timestamp() == MSG_TS   // the fallback, reached via a warning
    }
}
