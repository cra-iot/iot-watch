package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.FleetExportRange
import spock.lang.Specification

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class FleetExportRangeTest extends Specification {

    static final Instant NOW = Instant.parse("2026-11-12T09:30:00Z")

    def "parses an inclusive UTC range"() {
        when:
        def range = FleetExportRange.parse("2026-10-01", "2026-10-31", null, NOW)

        then:
        range.days() == 31
        range.start() == Instant.parse("2026-10-01T00:00:00Z")
        range.end() == Instant.parse("2026-11-01T00:00:00Z")
        range.utc()
    }

    def "rejects #why"() {
        when:
        FleetExportRange.parse(from, to, zone, NOW)

        then:
        thrown(IllegalArgumentException)

        where:
        why               | from         | to           | zone
        "from after to"   | "2026-10-31" | "2026-10-01" | null
        "today as to"     | "2026-11-01" | "2026-11-12" | null
        "367 days"        | "2025-11-09" | "2026-11-10" | null
        "a bad date"      | "2026-13-01" | "2026-11-10" | null
        "a null from"     | null         | "2026-11-10" | null
        "an unknown zone" | "2026-10-01" | "2026-10-31" | "Mars/Base"
    }

    def "accepts 366 days and yesterday"() {
        expect:
        FleetExportRange.parse("2025-11-11", "2026-11-11", null, NOW).days() == 366
    }

    def "yesterday follows the zone"() {
        given:
        def now = Instant.parse("2026-11-11T23:30:00Z")

        expect:
        FleetExportRange.maxTo(ZoneId.of("UTC"), now) == LocalDate.parse("2026-11-10")
        FleetExportRange.maxTo(ZoneId.of("Europe/Prague"), now) == LocalDate.parse("2026-11-11")
    }

    def "a zone shifts the bounds"() {
        expect:
        FleetExportRange.parse(day, day, "Europe/Prague", NOW).start() == Instant.parse(start)

        where:
        day          | start
        "2026-01-10" | "2026-01-09T23:00:00Z"
        "2026-07-10" | "2026-07-09T22:00:00Z"
    }

    def "DST change days count once"() {
        when:
        def range = FleetExportRange.parse("2026-03-28", "2026-03-30", "Europe/Prague", NOW)

        then:
        range.dates() == [LocalDate.parse("2026-03-28"), LocalDate.parse("2026-03-29"), LocalDate.parse("2026-03-30")]
        range.end() == Instant.parse("2026-03-30T22:00:00Z")
    }
}
