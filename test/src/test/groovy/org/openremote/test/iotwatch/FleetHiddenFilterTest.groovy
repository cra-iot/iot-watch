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

    def "a null filter element or a filter without a name is a 400, not a 500"() {
        given:
        def query = new FleetQuery()
        query.attributes = [filter]

        when:
        FleetResourceImpl.validateFilters(query)

        then:
        thrown(BadRequestException)

        where:
        filter << [null, new FleetQuery.AttributeFilter(), queryOn("  ").attributes[0]]
    }

    def "well-formed filters, a null operator and no filters pass validation"() {
        given:
        def withNullOperator = queryOn("battery_level")
        withNullOperator.attributes[0].operator = null

        when:
        FleetResourceImpl.validateFilters(withNullOperator)
        FleetResourceImpl.validateFilters(new FleetQuery())

        then:
        noExceptionThrown()
    }
}
