package org.openremote.test.iotwatch

import jakarta.ws.rs.BadRequestException
import org.openremote.manager.iotwatch.FleetResourceImpl
import org.openremote.model.iotwatch.FleetQuery
import spock.lang.Specification

// Runs without the container: the guard is a pure function of the query.
class FleetHiddenFilterTest extends Specification {

    static FleetQuery queryOn(String name) {
        def filter = new FleetQuery.AttributeFilter()
        filter.name = name
        def query = new FleetQuery()
        query.attributes = [filter]
        return query
    }

    def "rawValue filters are rejected in any case and with surrounding whitespace"() {
        when:
        FleetResourceImpl.rejectHiddenFilters(queryOn(name))

        then:
        thrown(BadRequestException)

        where:
        name << ["rawValue", "RAWVALUE", " rawvalue "]
    }

    def "other attribute filters and empty queries pass"() {
        when:
        FleetResourceImpl.rejectHiddenFilters(queryOn("battery_level"))
        FleetResourceImpl.rejectHiddenFilters(new FleetQuery())

        then:
        noExceptionThrown()
    }
}
