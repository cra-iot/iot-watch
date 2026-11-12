package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.DailyRows
import spock.lang.Specification

import java.time.Instant
import java.time.LocalDate

class DailyRowsTest extends Specification {

    static final List<LocalDate> DATES = (1..4).collect { LocalDate.of(2026, 10, it) }
    static final List<String> MEASUREMENTS = ["power_1", "power_2"]

    static DailyRows.Reading reading(String value, String at) {
        new DailyRows.Reading(value, Instant.parse(at))
    }

    def "carries values across empty days"() {
        when:
        def rows = DailyRows.build(DATES, MEASUREMENTS, [:],
                [(LocalDate.of(2026, 10, 1)): [power_1: reading("1.4", "2026-10-01T10:00:00Z")]])

        then:
        rows*.date() == DATES
        rows[1..3].every { it.values().power_1 == "1.4" }
        rows[1..3].every { it.lastReadingAt() == Instant.parse("2026-10-01T10:00:00Z") }
    }

    def "seed fills the first days"() {
        when:
        def rows = DailyRows.build(DATES, MEASUREMENTS,
                [power_1: reading("0.9", "2026-09-20T08:00:00Z")],
                [(LocalDate.of(2026, 10, 3)): [power_1: reading("1.1", "2026-10-03T09:00:00Z")]])

        then:
        rows[0].values().power_1 == "0.9"
        rows[1].values().power_1 == "0.9"
        rows[0].lastReadingAt() == Instant.parse("2026-09-20T08:00:00Z")
        rows[1].lastReadingAt() == Instant.parse("2026-09-20T08:00:00Z")
        rows[2].values().power_1 == "1.1"
    }

    def "different update times"() {
        when:
        def rows = DailyRows.build(DATES, MEASUREMENTS, [:],
                [(LocalDate.of(2026, 10, 1)): [power_1: reading("1.0", "2026-10-01T10:00:00Z"),
                                               power_2: reading("2.0", "2026-10-01T11:00:00Z")]])

        then:
        rows[0].values() == [power_1: "1.0", power_2: "2.0"]
        rows[0].lastReadingAt() == Instant.parse("2026-10-01T11:00:00Z")
    }

    def "no data at all"() {
        when:
        def rows = DailyRows.build(DATES, MEASUREMENTS, [:], [:])

        then:
        rows.size() == 4
        rows.every { it.values() == [power_1: null, power_2: null] }
        rows.every { it.lastReadingAt() == null }
    }

    def "stale meter stays visible"() {
        when:
        def rows = DailyRows.build(DATES, MEASUREMENTS,
                [power_1: reading("5.0", "2026-06-01T00:00:00Z")], [:])

        then:
        rows.every { it.lastReadingAt() == Instant.parse("2026-06-01T00:00:00Z") }
    }
}
