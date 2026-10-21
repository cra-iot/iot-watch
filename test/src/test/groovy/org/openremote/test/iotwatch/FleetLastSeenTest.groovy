package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.FleetResourceImpl
import org.openremote.model.attribute.Attribute
import org.openremote.model.value.ValueType
import spock.lang.Specification

// Runs without the container: the decision is a pure function of the attribute.
class FleetLastSeenTest extends Specification {

    def "an empty rawValue has no last seen although it carries a timestamp"() {
        given:
        def attribute = new Attribute<String>("rawValue", ValueType.TEXT)
        attribute.setTimestamp(1_000L)

        expect:
        FleetResourceImpl.payloadTimestamp(attribute) == null
    }

    def "a rawValue holding a payload reports its timestamp"() {
        given:
        def attribute = new Attribute<String>("rawValue", ValueType.TEXT, "payload")
        attribute.setTimestamp(2_000L)

        expect:
        FleetResourceImpl.payloadTimestamp(attribute) == 2_000L
    }
}
