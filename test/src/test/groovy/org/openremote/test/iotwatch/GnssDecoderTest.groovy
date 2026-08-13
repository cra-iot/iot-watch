package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.GnssDecoder
import org.openremote.model.geo.GeoJSONPoint
import spock.lang.Specification

class GnssDecoderTest extends Specification {

    GnssDecoder decoder = new GnssDecoder()
    Set<String> targets = ["location", "battery"] as Set

    def "maps gnss coordinates to a location point and battery_pct to battery"() {
        given:
        def message = [data_decoded: [gnss_latitude: 50.1d, gnss_longitude: 14.4d, battery_pct: 87]]

        when:
        def events = decoder.decode("t1", message, targets, 2000L)

        then:
        def loc = events.find { it.ref.name == "location" }
        loc != null
        (loc.value.get() as GeoJSONPoint).longitude == 14.4d
        (loc.value.get() as GeoJSONPoint).latitude == 50.1d
        loc.timestamp == 2000L

        def bat = events.find { it.ref.name == "battery" }
        bat.value.get() == 87.0d
    }

    def "omits an attribute that is not provisioned (not in targetNames)"() {
        given:
        def message = [data_decoded: [gnss_latitude: 50.1d, gnss_longitude: 14.4d, battery_pct: 87]]

        when:
        def events = decoder.decode("t1", message, ["battery"] as Set, 2000L)

        then:
        events*.ref*.name == ["battery"]
    }

    def "handles partial or missing data"() {
        expect:
        decoder.decode("t1", [data_decoded: [battery_pct: 87]] as Map, targets, 1L)*.ref*.name == ["battery"]
        decoder.decode("t1", [:] as Map, targets, 1L).isEmpty()
    }
}
