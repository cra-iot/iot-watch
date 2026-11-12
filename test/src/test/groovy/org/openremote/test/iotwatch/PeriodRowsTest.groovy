package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.PeriodRows
import spock.lang.Specification

import java.time.Instant
import java.time.LocalDate

class PeriodRowsTest extends Specification {

    static final LocalDate P1 = LocalDate.of(2026, 9, 1)
    static final LocalDate P2 = LocalDate.of(2026, 10, 1)
    static final List<LocalDate> PERIODS = [P1, P2]
    static final List<String> ATTRIBUTES = ["a", "b"]
    static final Instant T0 = Instant.parse("2026-08-20T08:00:00Z")
    static final Instant T1 = Instant.parse("2026-09-15T10:00:00Z")

    static PeriodRows.Reading reading(String value, Instant at) {
        new PeriodRows.Reading(value, at)
    }

    static PeriodRows.Values values(String start, String end, String consumption) {
        new PeriodRows.Values(start, end, consumption)
    }

    def "start carries the seed and end the period's reading"() {
        when:
        def rows = PeriodRows.build(PERIODS, ATTRIBUTES, [a: reading("100", T0)],
                [(P1): [a: reading("110", T1)]])

        then:
        rows*.period() == PERIODS
        rows[0].values().a == values("100", "110", "10")
        rows[1].values().a == values("110", "110", "0")
        rows[1].endReadingAt() == T1
    }

    def "silent meter keeps its old timestamp"() {
        when:
        def rows = PeriodRows.build(PERIODS, ATTRIBUTES, [a: reading("50", T0)], [:])

        then:
        rows.every { it.values().a == values("50", "50", "0") }
        rows.every { it.endReadingAt() == T0 }
    }

    def "no reading at all gives empty cells but every row"() {
        when:
        def rows = PeriodRows.build(PERIODS, ATTRIBUTES, [:], [:])

        then:
        rows.size() == 2
        rows.every { it.values().a == values(null, null, null) }
        rows.every { it.endReadingAt() == null }
    }

    def "missing second group stays empty"() {
        when:
        def rows = PeriodRows.build(PERIODS, ATTRIBUTES, [a: reading("1", T0)],
                [(P1): [a: reading("2", T1)]])

        then:
        rows.every { it.values().b == values(null, null, null) }
        rows[0].values().a == values("1", "2", "1")
        rows[0].values().keySet().toList() == ATTRIBUTES
    }

    def "consumption is exact decimal"() {
        when:
        def first = PeriodRows.build(PERIODS, ATTRIBUTES, [a: reading("0.1", T0)],
                [(P1): [a: reading("0.3", T1)]])
        def second = PeriodRows.build(PERIODS, ATTRIBUTES, [a: reading("1E+3", T0)],
                [(P1): [a: reading("1500", T1)]])

        then:
        first[0].values().a.consumption() == "0.2"
        second[0].values().a.start() == "1000"
        second[0].values().a.consumption() == "500"
    }

    def "non-numeric values do not throw"() {
        when:
        def rows = PeriodRows.build(PERIODS, ATTRIBUTES, [a: reading("abc", T0)],
                [(P1): [a: reading("5", T1)]])

        then:
        rows[0].values().a.start() == "abc"
        rows[0].values().a.end() == "5"
        rows[0].values().a.consumption() == null
    }

    def "negative consumption is kept"() {
        when:
        def rows = PeriodRows.build(PERIODS, ATTRIBUTES, [a: reading("900", T0)],
                [(P1): [a: reading("10", T1)]])

        then:
        rows[0].values().a.consumption() == "-890"
    }

    def "empty attribute list does not throw"() {
        when:
        def rows = PeriodRows.build(PERIODS, [], [:], [:])

        then:
        rows.size() == 2
        rows.every { it.endReadingAt() == null && it.values().isEmpty() }
    }

    def "number renders plain, passes through text and null"() {
        expect:
        PeriodRows.number("1E+3") == "1000"
        PeriodRows.number("2.50") == "2.5"
        PeriodRows.number("abc") == "abc"
        PeriodRows.number(null) == null
    }

    def "a huge exponent is treated as text"() {
        expect:
        PeriodRows.number("1E+999999999") == "1E+999999999"
        PeriodRows.number("1E-999999999") == "1E-999999999"

        when:
        def rows = PeriodRows.build(PERIODS, ATTRIBUTES, [a: reading("1E+999999999", T0)],
                [(P1): [a: reading("10", T1)]])

        then:
        rows[0].values().a.start() == "1E+999999999"
        rows[0].values().a.consumption() == null
    }
}
