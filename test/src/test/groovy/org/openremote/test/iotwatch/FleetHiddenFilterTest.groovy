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

    def "an unknown or null status is a 400, not a 500"() {
        given:
        def query = new FleetQuery()
        query.status = status

        when:
        FleetResourceImpl.validateFilters(query)

        then:
        thrown(BadRequestException)

        where:
        status << [["Alarm"], ["silent "], ["ok", "broken"], [null]]
    }

    def "the known statuses pass validation"() {
        given:
        def query = new FleetQuery()
        query.status = ["alarm", "silent", "ok"]

        when:
        FleetResourceImpl.validateFilters(query)

        then:
        noExceptionThrown()
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

    static FleetQuery filterQuery(FleetQuery.Operator operator, Object value, Object rangeValue = null) {
        def query = queryOn("battery_level")
        query.attributes[0].operator = operator
        query.attributes[0].value = value
        query.attributes[0].rangeValue = rangeValue
        return query
    }

    def "an operator that does not fit its value is a 400: #operator with #value"() {
        when:
        FleetResourceImpl.validateOperators(filterQuery(operator, value, rangeValue))

        then:
        thrown(BadRequestException)

        where:
        operator                              | value | rangeValue
        FleetQuery.Operator.CONTAINS          | 5     | null
        FleetQuery.Operator.CONTAINS          | true  | null
        FleetQuery.Operator.STARTS_WITH       | 5.5d  | null
        FleetQuery.Operator.STARTS_WITH       | false | null
        FleetQuery.Operator.LESS_THAN         | "5"   | null
        FleetQuery.Operator.LESS_THAN         | true  | null
        FleetQuery.Operator.LESS_EQUALS       | "5"   | null
        FleetQuery.Operator.LESS_EQUALS       | false | null
        FleetQuery.Operator.GREATER_THAN      | "5"   | null
        FleetQuery.Operator.GREATER_THAN      | true  | null
        FleetQuery.Operator.GREATER_EQUALS    | "5"   | null
        FleetQuery.Operator.GREATER_EQUALS    | true  | null
        FleetQuery.Operator.GREATER_EQUALS    | null  | null
        FleetQuery.Operator.BETWEEN           | "5"   | 10
        FleetQuery.Operator.BETWEEN           | true  | 10
        FleetQuery.Operator.BETWEEN           | 5     | null
        FleetQuery.Operator.BETWEEN           | 5     | "10"
    }

    def "operators that fit their value pass: #operator with #value"() {
        when:
        FleetResourceImpl.validateOperators(filterQuery(operator, value, rangeValue))

        then:
        noExceptionThrown()

        where:
        operator                              | value  | rangeValue
        FleetQuery.Operator.EQUALS            | "X"    | null
        FleetQuery.Operator.EQUALS            | 5      | null
        FleetQuery.Operator.EQUALS            | true   | null
        FleetQuery.Operator.NOT_EQUALS        | "X"    | null
        FleetQuery.Operator.NOT_EQUALS        | 5      | null
        FleetQuery.Operator.NOT_EQUALS        | true   | null
        FleetQuery.Operator.CONTAINS          | "X"    | null
        FleetQuery.Operator.STARTS_WITH       | "X"    | null
        FleetQuery.Operator.LESS_THAN         | 5      | null
        FleetQuery.Operator.GREATER_EQUALS    | 5.5d   | null
        FleetQuery.Operator.BETWEEN           | 5      | 10
        null                                  | 5      | null
    }
}
