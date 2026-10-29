package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.FleetResultAssembler
import org.openremote.model.iotwatch.FleetQuery
import org.openremote.model.iotwatch.FleetRow
import spock.lang.Specification

class FleetResultAssemblerTest extends Specification {

    static final long NOW = 1_700_000_000_000L
    static final long HOUR = 3_600_000L

    static FleetRow row(String id, String name, String type, Long lastSeen, List<String> alarms = []) {
        def r = new FleetRow()
        r.id = id
        r.name = name
        r.type = type
        r.lastSeen = lastSeen
        r.alarms = alarms
        return r
    }

    def "duplicate ids from the two text queries are collapsed once"() {
        given:
        def rows = [row("a", "A", "GasMeterAsset", NOW), row("a", "A", "GasMeterAsset", NOW),
                    row("b", "B", "GasMeterAsset", NOW)]

        when:
        def result = FleetResultAssembler.assemble(rows, new FleetQuery(), NOW)

        then:
        result.total == 2
        result.rows*.id == ["a", "b"]
    }

    def "olderThan matches stale assets and assets that never reported"() {
        given:
        def rows = [row("fresh", "Fresh", "GasMeterAsset", NOW - HOUR),
                    row("stale", "Stale", "GasMeterAsset", NOW - 48 * HOUR),
                    row("never", "Never", "GasMeterAsset", null)]
        def query = new FleetQuery()
        query.seen = new FleetQuery.SeenFilter()
        query.seen.olderThan = "PT24H"

        when:
        def result = FleetResultAssembler.assemble(rows, query, NOW)

        then:
        result.rows*.id as Set == ["stale", "never"] as Set
    }

    def "newerThan never matches an asset that has not reported"() {
        given:
        def rows = [row("fresh", "Fresh", "GasMeterAsset", NOW - HOUR),
                    row("never", "Never", "GasMeterAsset", null)]
        def query = new FleetQuery()
        query.seen = new FleetQuery.SeenFilter()
        query.seen.newerThan = "PT24H"

        when:
        def result = FleetResultAssembler.assemble(rows, query, NOW)

        then:
        result.rows*.id == ["fresh"]
    }

    def "sorting by lastSeen puts never-reported assets last in both directions"() {
        given:
        def rows = [row("never", "N", "GasMeterAsset", null),
                    row("old", "O", "GasMeterAsset", NOW - 48 * HOUR),
                    row("new", "W", "GasMeterAsset", NOW)]
        def query = new FleetQuery()
        query.sort = new FleetQuery.Sort()
        query.sort.field = "lastSeen"

        when:
        query.sort.descending = false
        def ascending = FleetResultAssembler.assemble(rows, query, NOW)
        query.sort.descending = true
        def descending = FleetResultAssembler.assemble(rows, query, NOW)

        then:
        ascending.rows*.id == ["old", "new", "never"]
        descending.rows*.id == ["new", "old", "never"]
    }

    static List<FleetRow> volumeRows() {
        // Names deliberately in the opposite order to the volumes, so a name sort is visible.
        def a = row("a", "C", "GasMeterAsset", NOW); a.values = [volume: 5d]
        def b = row("b", "B", "GasMeterAsset", NOW); b.values = [volume: 20]
        def c = row("c", "A", "GasMeterAsset", NOW); c.values = [volume: null]
        def d = row("d", "D", "GasMeterAsset", NOW)
        return [a, b, c, d]
    }

    static FleetQuery sortedBy(String field, boolean descending) {
        def query = new FleetQuery()
        query.sort = new FleetQuery.Sort()
        query.sort.field = field
        query.sort.descending = descending
        return query
    }

    def "a bare attribute name sorts numerically by that value, missing values last in both directions"() {
        expect: "the UI's column key is the bare descriptor name, not values.<name>"
        FleetResultAssembler.assemble(volumeRows(), sortedBy("volume", false), NOW).rows*.id == ["a", "b", "c", "d"]
        FleetResultAssembler.assemble(volumeRows(), sortedBy("volume", true), NOW).rows*.id == ["b", "a", "c", "d"]
    }

    def "the values.<name> alias sorts the same attribute"() {
        expect:
        FleetResultAssembler.assemble(volumeRows(), sortedBy("values.volume", false), NOW).rows*.id == ["a", "b", "c", "d"]
        FleetResultAssembler.assemble(volumeRows(), sortedBy("values.volume", true), NOW).rows*.id == ["b", "a", "c", "d"]
    }

    def "name and createdOn sort by name"() {
        expect:
        FleetResultAssembler.assemble(volumeRows(), sortedBy("name", false), NOW).rows*.id == ["c", "b", "a", "d"]
        FleetResultAssembler.assemble(volumeRows(), sortedBy("createdOn", true), NOW).rows*.id == ["d", "a", "b", "c"]
    }

    def "facets and total count the whole match, not the returned page"() {
        given:
        def rows = (1..10).collect {
            row("id$it", "Meter $it", it <= 6 ? "GasMeterAsset" : "WaterMeterAsset",
                    NOW, it == 1 ? ["leakage"] : [])
        }
        def query = new FleetQuery()
        query.limit = 3

        when:
        def result = FleetResultAssembler.assemble(rows, query, NOW)

        then:
        result.rows.size() == 3
        result.total == 10
        result.facets.types.find { it.key == "GasMeterAsset" }.count == 6
        result.facets.types.find { it.key == "WaterMeterAsset" }.count == 4
        result.facets.alarms.find { it.key == "leakage" }.count == 1
    }

    def "facet problems count alarm and silent rows over the whole match, not the page"() {
        given:
        def rows = (1..6).collect {
            row("id$it", "Meter $it", "GasMeterAsset",
                    it == 5 ? NOW - 48 * HOUR : NOW, it <= 2 ? ["leakage"] : [])
        } + [row("w1", "Water", "WaterMeterAsset", NOW)]
        def query = new FleetQuery()
        query.limit = 2

        when:
        def result = FleetResultAssembler.assemble(rows, query, NOW)
        def gas = result.facets.types.find { it.key == "GasMeterAsset" }
        def water = result.facets.types.find { it.key == "WaterMeterAsset" }

        then:
        result.rows.size() == 2
        gas.count == 6
        gas.problems == 3
        water.problems == 0
        result.facets.status.find { it.key == "ok" }.problems == 0
        result.facets.status.find { it.key == "alarm" }.problems == 2
        result.facets.alarms.find { it.key == "leakage" }.problems == 2
    }

    def "status facet buckets alarm, silent and ok without double counting ok"() {
        given:
        def rows = [row("alarm", "A", "GasMeterAsset", NOW, ["leakage"]),
                    row("silent", "S", "GasMeterAsset", NOW - 48 * HOUR),
                    row("ok", "O", "GasMeterAsset", NOW)]

        when:
        def result = FleetResultAssembler.assemble(rows, new FleetQuery(), NOW)
        def status = result.facets.status.collectEntries { [it.key, it.count] }

        then:
        status["alarm"] == 1
        status["silent"] == 1
        status["ok"] == 1
    }

    def "a status filter keeps the rows of the given statuses, ORed, and status counts ignore it"() {
        given:
        def rows = [row("alarm", "A", "GasMeterAsset", NOW, ["leakage"]),
                    row("silent", "S", "GasMeterAsset", NOW - 48 * HOUR),
                    row("never", "N", "GasMeterAsset", null),
                    row("ok", "O", "GasMeterAsset", NOW)]
        def query = new FleetQuery()
        query.status = ["alarm", "silent"]

        when:
        def result = FleetResultAssembler.assemble(rows, query, NOW)

        then: "the match, its facets and the export hold the filtered rows only"
        result.rows*.id == ["alarm", "never", "silent"]
        result.total == 3
        result.facets.status.collectEntries { [it.key, it.count] } == [silent: 2, alarm: 1]
        FleetResultAssembler.match(rows, query, NOW)*.id == ["alarm", "never", "silent"]

        and: "status counts still name every status"
        result.statusCounts.collectEntries { [it.key, it.count] } == [silent: 2, alarm: 1, ok: 1]
    }

    def "match returns every row the query selected, ignoring the page limit"() {
        given: "more rows than any page would return"
        def rows = (1..600).collect { row("id$it", "M$it", "GasMeterAsset", NOW) }
        def query = new FleetQuery()
        query.limit = 10

        expect: "the export path sees all 600, the overview path sees its page"
        FleetResultAssembler.match(rows, query, NOW).size() == 600
        FleetResultAssembler.assemble(rows, query, NOW).rows.size() == 10
    }

    def "an empty query matches the whole realm, which is what export-all means"() {
        given:
        def rows = (1..25).collect { row("id$it", "M$it", "GasMeterAsset", NOW) }

        expect:
        FleetResultAssembler.match(rows, new FleetQuery(), NOW).size() == 25
    }

    def "a malformed duration is rejected as the caller's error"() {
        given:
        def query = new FleetQuery()
        query.seen = new FleetQuery.SeenFilter()
        query.seen.olderThan = "24 hours"

        when:
        FleetResultAssembler.assemble([row("a", "A", "GasMeterAsset", NOW)], query, NOW)

        then: "FleetResourceImpl maps this to 400"
        thrown(IllegalArgumentException)
    }

    def "a malformed duration is rejected even when nothing matched"() {
        given:
        def query = new FleetQuery()
        query.seen = new FleetQuery.SeenFilter()
        query.seen.newerThan = "yesterday"

        when: "zero rows, so a per-row parse would never run"
        FleetResultAssembler.match([], query, NOW)

        then:
        thrown(IllegalArgumentException)
    }

    def "grouping keeps each group contiguous across pages"() {
        given: "two parents whose rows would interleave if sorted by name only"
        def rows = [row("1", "A", "GasMeterAsset", NOW), row("2", "B", "GasMeterAsset", NOW),
                    row("3", "C", "GasMeterAsset", NOW), row("4", "D", "GasMeterAsset", NOW)]
        rows[0].parentId = "p2"; rows[1].parentId = "p1"; rows[2].parentId = "p2"; rows[3].parentId = "p1"
        def query = new FleetQuery()
        query.groupBy = FleetQuery.GroupBy.PARENT

        when:
        def matched = FleetResultAssembler.match(rows, query, NOW)

        then:
        matched*.parentId == ["p1", "p1", "p2", "p2"]
        matched*.name == ["B", "D", "A", "C"]
    }

    def "collapsed groups leave the paging but stay in total and facets"() {
        given: "more electricity meters than a page, sorting before the gas meters"
        def rows = (1..5).collect { row("e$it", "E$it", "ElectricMeterAsset", NOW) } +
                (1..3).collect { row("g$it", "G$it", "GasMeterAsset", NOW) }
        def query = new FleetQuery()
        query.groupBy = FleetQuery.GroupBy.TYPE
        query.limit = 2

        when: "nothing is collapsed"
        def open = FleetResultAssembler.assemble(rows, query, NOW)

        then: "the first page holds only electricity meters"
        open.rows*.type == ["ElectricMeterAsset", "ElectricMeterAsset"]
        open.paged == 8

        when: "the electricity meters are collapsed"
        query.collapsed = ["ElectricMeterAsset"]
        def collapsed = FleetResultAssembler.assemble(rows, query, NOW)

        then: "the gas meters fill the pages, the counts still describe the whole match"
        collapsed.rows*.name == ["G1", "G2"]
        collapsed.paged == 3
        collapsed.total == 8
        collapsed.facets.types.find { it.key == "ElectricMeterAsset" }.count == 5
    }

    def "collapsed keys are ignored without grouping"() {
        given:
        def rows = [row("1", "A", "ElectricMeterAsset", NOW), row("2", "B", "GasMeterAsset", NOW)]
        def query = new FleetQuery()
        query.collapsed = ["ElectricMeterAsset"]

        when:
        def result = FleetResultAssembler.assemble(rows, query, NOW)

        then:
        result.rows*.name == ["A", "B"]
        result.paged == 2
    }

    def "an oversized limit is capped and a zero limit uses the default"() {
        given:
        def rows = (1..600).collect { row("id$it", "M$it", "GasMeterAsset", NOW) }

        when:
        def capped = new FleetQuery()
        capped.limit = 5000
        def defaulted = new FleetQuery()

        then:
        FleetResultAssembler.assemble(rows, capped, NOW).rows.size() == FleetResultAssembler.MAX_LIMIT
        FleetResultAssembler.assemble(rows, defaulted, NOW).rows.size() == FleetResultAssembler.DEFAULT_LIMIT
    }

    def "unmatched free-text values are named over the whole match, not the page"() {
        given:
        def a = row("a", "Albertova 23 A", "WaterMeterAsset", NOW)
        a.devEui = "70B3D5D0003E8A1D"
        def b = row("b", "Hall", "WaterMeterAsset", NOW)
        b.externalId = "EXT-77"
        def stale = row("stale", "Stale", "WaterMeterAsset", NOW - 48 * HOUR)
        stale.meterId = "W49651272"
        def query = new FleetQuery()
        query.text = ["70b3d5d0003e8a1d", "EXT-77", "W49651272", "MISSING-1"]
        query.seen = new FleetQuery.SeenFilter()
        query.seen.newerThan = "PT24H"
        query.limit = 1

        when:
        def result = FleetResultAssembler.assemble([a, b, stale], query, NOW)

        then: "a value matched only by a row the seen filter dropped is unmatched too"
        result.rows.size() == 1
        result.unmatchedText == ["W49651272", "MISSING-1"]
    }

    def "a value only a status filter hides is not unmatched: the meter was found"() {
        given:
        def alarm = row("alarm", "A", "WaterMeterAsset", NOW, ["leakage"])
        alarm.devEui = "70B3D5D0003E8A1D"
        def ok = row("ok", "O", "WaterMeterAsset", NOW)
        ok.devEui = "70B3D5D0003E8A1E"
        def query = new FleetQuery()
        query.text = ["70B3D5D0003E8A1D", "70B3D5D0003E8A1E", "MISSING-1"]
        query.status = ["alarm"]

        when:
        def result = FleetResultAssembler.assemble([alarm, ok], query, NOW)

        then:
        result.rows*.id == ["alarm"]
        result.unmatchedText == ["MISSING-1"]
    }

    def "without free text nothing is unmatched"() {
        expect:
        FleetResultAssembler.assemble([row("a", "A", "GasMeterAsset", NOW)], new FleetQuery(), NOW).unmatchedText == []
    }
}
