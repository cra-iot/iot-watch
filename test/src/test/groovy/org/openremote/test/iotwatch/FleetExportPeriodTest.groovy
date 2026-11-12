package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.FleetExportPeriod
import org.openremote.model.iotwatch.FleetExportReport
import spock.lang.Specification

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

import static org.openremote.model.iotwatch.FleetExportReport.DAILY
import static org.openremote.model.iotwatch.FleetExportReport.MONTHLY

class FleetExportPeriodTest extends Specification {

    static final Instant NOW = Instant.parse("2026-11-12T09:30:00Z")

    def "monthly periods are month starts"() {
        when:
        def period = FleetExportPeriod.parse(MONTHLY, "2025-11-01", "2026-10-01", NOW)

        then:
        period.periods().size() == 12
        period.periods().first() == LocalDate.parse("2025-11-01")
        period.periods().last() == LocalDate.parse("2026-10-01")
        period.label(LocalDate.parse("2026-10-01")) == "2026-10"
        period.column() == "month"
        period.fileKey() == "monthly"
    }

    def "daily periods are days"() {
        when:
        def period = FleetExportPeriod.parse(DAILY, "2026-10-30", "2026-11-02", NOW)

        then:
        period.periods() == [LocalDate.parse("2026-10-30"), LocalDate.parse("2026-10-31"),
                             LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-02")]
        period.label(LocalDate.parse("2026-10-31")) == "2026-10-31"
        period.column() == "date"
        period.fileKey() == "daily"
    }

    def "bounds are local midnight across DST"() {
        expect:
        FleetExportPeriod.parse(MONTHLY, "2026-10-01", "2026-10-01", NOW).start() == Instant.parse("2026-09-30T22:00:00Z")
        FleetExportPeriod.parse(MONTHLY, "2026-10-01", "2026-10-01", NOW).end() == Instant.parse("2026-10-31T23:00:00Z")
        FleetExportPeriod.parse(DAILY, "2026-03-29", "2026-03-29", NOW).start() == Instant.parse("2026-03-28T23:00:00Z")
        FleetExportPeriod.parse(DAILY, "2026-03-29", "2026-03-29", NOW).end() == Instant.parse("2026-03-29T22:00:00Z")
    }

    def "rejects #message"() {
        when:
        FleetExportPeriod.parse(report as FleetExportReport, from, to, NOW)

        then:
        def e = thrown(IllegalArgumentException)
        e.message == message

        where:
        report  | from         | to           | message
        MONTHLY | "2026-10-02" | "2026-10-01" | "Months must start on the first day"
        MONTHLY | "2026-11-01" | "2026-11-01" | "The last month must be last month or earlier"
        DAILY   | "2026-11-12" | "2026-11-12" | "The last day must be yesterday or earlier"
        DAILY   | "2025-11-01" | "2026-11-02" | "The range must not exceed 366 days"
        MONTHLY | "2024-10-01" | "2026-10-01" | "The range must not exceed 24 months"
        DAILY   | "2026-10-05" | "2026-10-01" | "The first period must not be after the last"
        DAILY   | "x"          | "2026-10-01" | "Dates must be given as yyyy-MM-dd"
        null    | "2026-10-01" | "2026-10-01" | "Select a report"
    }

    def "limits are inclusive"() {
        expect:
        FleetExportPeriod.parse(MONTHLY, "2024-11-01", "2026-10-01", NOW).count() == 24
        FleetExportPeriod.parse(DAILY, "2025-11-11", "2026-11-11", NOW).count() == 366
    }

    def "last periods"() {
        expect:
        FleetExportPeriod.lastDay(NOW) == LocalDate.parse("2026-11-11")
        // already 1 November in Prague
        FleetExportPeriod.lastMonth(Instant.parse("2026-10-31T23:30:00Z")) == YearMonth.parse("2026-10")
    }
}
