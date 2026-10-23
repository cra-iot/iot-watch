package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.FleetColumnResolver
import org.openremote.manager.iotwatch.FleetQueryTranslator
import org.openremote.model.asset.Asset
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.iotwatch.FleetQuery
import org.openremote.model.query.filter.BooleanPredicate
import org.openremote.model.query.filter.NumberPredicate
import org.openremote.model.query.filter.StringPredicate
import org.openremote.model.query.AssetQuery
import org.openremote.model.util.ValueUtil
import spock.lang.Specification

class FleetQueryTranslatorTest extends Specification {

    /**
     * A resolved select, as FleetColumnResolver.selectAttributes would return it. The translator
     * is not responsible for deciding this - it only has to pass it through to every branch.
     */
    static final String[] SELECT = [
            "delivery_point_id", "meter_id", "devEui", "external_id", "battery_level",
            "volume", "low_battery", "tamper"
    ] as String[]

    def setupSpec() {
        ValueUtil.initialise(null)
    }

    static FleetQuery.AttributeFilter filter(String name, FleetQuery.Operator op, Object value) {
        def f = new FleetQuery.AttributeFilter()
        f.name = name
        f.operator = op
        f.value = value
        return f
    }

    def "realm and the resolved attribute selection are always applied"() {
        when:
        def q = FleetQueryTranslator.filterQuery(new FleetQuery(), "acme", SELECT)

        then:
        q.realm.name == "acme"
        q.select.attributes as Set == SELECT as Set
        !q.select.attributes.contains("rawValue")
        q.select.attributes.contains("delivery_point_id")
    }

    def "type and parent tokens map to types and parents"() {
        given:
        def fq = new FleetQuery()
        fq.types = ["GasMeterAsset"]
        fq.parentIds = ["parent-1"]
        fq.recursive = true

        when:
        def q = FleetQueryTranslator.filterQuery(fq, "acme", SELECT)

        then:
        q.types == ["GasMeterAsset"] as String[]
        q.parents.length == 1
        q.parents[0].id == "parent-1"
        q.recursive
    }

    def "a boolean attribute filter becomes a BooleanPredicate"() {
        given:
        def fq = new FleetQuery()
        fq.attributes = [filter("leakage", FleetQuery.Operator.EQUALS, true)]

        when:
        def q = FleetQueryTranslator.filterQuery(fq, "acme", SELECT)
        def item = q.attributes.items[0]

        then:
        item.name.value == "leakage"
        item.value instanceof BooleanPredicate
        ((BooleanPredicate) item.value).value
    }

    def "a numeric attribute filter carries the operator"() {
        given:
        def fq = new FleetQuery()
        fq.attributes = [filter("battery_level", FleetQuery.Operator.LESS_THAN, 20)]

        when:
        def q = FleetQueryTranslator.filterQuery(fq, "acme", SELECT)
        def pred = (NumberPredicate) q.attributes.items[0].value

        then:
        pred.value == 20d
        pred.operator == AssetQuery.Operator.LESS_THAN
    }

    def "BETWEEN carries the range value"() {
        given:
        def fq = new FleetQuery()
        def f = filter("currentReading", FleetQuery.Operator.BETWEEN, 10)
        f.rangeValue = 20
        fq.attributes = [f]

        when:
        def pred = (NumberPredicate) FleetQueryTranslator.filterQuery(fq, "acme", SELECT).attributes.items[0].value

        then:
        pred.value == 10d
        pred.rangeValue == 20d
        pred.operator == AssetQuery.Operator.BETWEEN
    }

    def "free text produces a name query and an identifier OR query carrying the same tokens"() {
        given:
        def fq = new FleetQuery()
        fq.text = ["kotelna"]
        fq.types = ["GasMeterAsset"]

        when:
        def nameQuery = FleetQueryTranslator.nameTextQuery(fq, "acme", SELECT)
        def idQuery = FleetQueryTranslator.identifierTextQuery(fq, "acme", SELECT)

        then:
        nameQuery.names.length == 1
        nameQuery.names[0].value == "kotelna"
        nameQuery.names[0].match == AssetQuery.Match.CONTAINS
        !nameQuery.names[0].caseSensitive
        nameQuery.types == ["GasMeterAsset"] as String[]

        and:
        idQuery.attributes.operator == org.openremote.model.query.LogicGroup.Operator.OR
        idQuery.attributes.items*.name*.value as Set ==
                ["delivery_point_id", "meter_id", "devEui", "external_id"] as Set
        idQuery.attributes.items.every { ((StringPredicate) it.value).match == AssetQuery.Match.CONTAINS }
        idQuery.types == ["GasMeterAsset"] as String[]
    }

    def "token filters and the identifier OR are ANDed, not flattened"() {
        given:
        def fq = new FleetQuery()
        fq.text = ["kotelna"]
        fq.attributes = [filter("leakage", FleetQuery.Operator.EQUALS, true)]

        when:
        def idQuery = FleetQueryTranslator.identifierTextQuery(fq, "acme", SELECT)

        then: "the outer group is AND: one sub-group for the identifiers, one item per token"
        idQuery.attributes.operator == org.openremote.model.query.LogicGroup.Operator.AND
        idQuery.attributes.groups.size() == 1
        idQuery.attributes.groups[0].operator == org.openremote.model.query.LogicGroup.Operator.OR
        idQuery.attributes.items[0].name.value == "leakage"
    }

    def "no free text means no text queries"() {
        expect:
        FleetQueryTranslator.nameTextQuery(new FleetQuery(), "acme", SELECT) == null
        FleetQueryTranslator.identifierTextQuery(new FleetQuery(), "acme", SELECT) == null
    }

    def "an empty type filter is narrowed to the device types, an explicit one is kept"() {
        when:
        def emptyFilter = FleetQueryTranslator.filterQuery(new FleetQuery(), "acme", SELECT)
        def fq = new FleetQuery()
        fq.types = ["ThingAsset"]
        def explicit = FleetQueryTranslator.filterQuery(fq, "acme", SELECT)

        then: "buildings and other non-device assets are not listed as silent devices"
        emptyFilter.types as List == FleetColumnResolver.deviceTypes()
        (emptyFilter.types as List).containsAll(["WaterMeterAsset", "GasMeterAsset", "TrackerAsset"])
        !(emptyFilter.types as List).contains("ThingAsset")

        and: "an explicit type token still reaches any type"
        explicit.types == ["ThingAsset"] as String[]
    }

    def "the free-text branches carry the same narrowed types"() {
        given:
        def fq = new FleetQuery()
        fq.text = ["kotelna"]

        expect:
        FleetQueryTranslator.nameTextQuery(fq, "acme", SELECT).types as List == FleetColumnResolver.deviceTypes()
        FleetQueryTranslator.identifierTextQuery(fq, "acme", SELECT).types as List == FleetColumnResolver.deviceTypes()
    }

    def "an explicit null operator means EQUALS rather than a server error"() {
        given:
        def fq = new FleetQuery()
        fq.attributes = [filter("battery_level", null, 20), filter("meter_id", null, "TEST")]

        when:
        def items = FleetQueryTranslator.filterQuery(fq, "acme", SELECT).attributes.items

        then:
        ((NumberPredicate) items[0].value).operator == AssetQuery.Operator.EQUALS
        ((StringPredicate) items[1].value).match == AssetQuery.Match.EXACT
    }

    def "NOT_EQUALS becomes a negated EQUALS predicate for every value type"() {
        given:
        def fq = new FleetQuery()
        fq.attributes = [filter("battery_level", FleetQuery.Operator.NOT_EQUALS, 20),
                         filter("meter_id", FleetQuery.Operator.NOT_EQUALS, "TEST"),
                         filter("leakage", FleetQuery.Operator.NOT_EQUALS, true)]

        when:
        def items = FleetQueryTranslator.filterQuery(fq, "acme", SELECT).attributes.items

        then:
        items.every { it.negated }
        ((NumberPredicate) items[0].value).operator == AssetQuery.Operator.EQUALS
        ((NumberPredicate) items[0].value).value == 20d
        ((StringPredicate) items[1].value).match == AssetQuery.Match.EXACT
        ((StringPredicate) items[1].value).value == "TEST"
        ((BooleanPredicate) items[2].value).value
    }

    def "a negated NOT_EQUALS is a plain EQUALS, and a negated EQUALS stays negated"() {
        given:
        def notNotEquals = filter("meter_id", FleetQuery.Operator.NOT_EQUALS, "TEST")
        notNotEquals.negate = true
        def notEquals = filter("meter_id", FleetQuery.Operator.EQUALS, "TEST")
        notEquals.negate = true
        def fq = new FleetQuery()
        fq.attributes = [notNotEquals, notEquals]

        when:
        def items = FleetQueryTranslator.filterQuery(fq, "acme", SELECT).attributes.items

        then:
        !items[0].negated
        ((StringPredicate) items[0].value).match == AssetQuery.Match.EXACT
        items[1].negated
    }

    def "with a site, the name match leaves SQL: names apply only to the first recursion level"() {
        given:
        def fq = new FleetQuery()
        fq.text = ["kotelna"]
        fq.parentIds = ["building-1"]
        fq.recursive = true

        when:
        def nameQuery = FleetQueryTranslator.nameTextQuery(fq, "acme", SELECT)

        then: "the query still carries the site and the tokens, but no names"
        FleetQueryTranslator.matchesNameInJava(fq)
        nameQuery.names == null
        nameQuery.parents*.id == ["building-1"]
        nameQuery.recursive

        and: "the identifier branch is unchanged"
        FleetQueryTranslator.identifierTextQuery(fq, "acme", SELECT).attributes.operator ==
                org.openremote.model.query.LogicGroup.Operator.OR
    }

    def "without a site, or without text, the name match stays in SQL"() {
        given:
        def textOnly = new FleetQuery()
        textOnly.text = ["kotelna"]
        def siteOnly = new FleetQuery()
        siteOnly.parentIds = ["building-1"]
        siteOnly.recursive = true

        expect:
        !FleetQueryTranslator.matchesNameInJava(textOnly)
        FleetQueryTranslator.nameTextQuery(textOnly, "acme", SELECT).names[0].value == "kotelna"
        !FleetQueryTranslator.matchesNameInJava(siteOnly)
        FleetQueryTranslator.nameTextQuery(siteOnly, "acme", SELECT) == null
    }

    def "the Java name filter keeps names containing the text, ignoring case"() {
        given:
        List<Asset<?>> assets = [new ThingAsset("Kotelna sever"), new ThingAsset("Meter in KOTELNA"),
                                 new ThingAsset("Hall")]

        expect:
        FleetQueryTranslator.filterByName(assets, ["kotelna"])*.name == ["Kotelna sever", "Meter in KOTELNA"]
        FleetQueryTranslator.filterByName(assets, ["missing"]).isEmpty()
    }

    def "several free-text values are ORed in the names and in the one identifier group"() {
        given:
        def fq = new FleetQuery()
        fq.text = ["70B3D5D0003E8A1D", " 70B3D5448FA36861 ", "", "70B3D5D0003E8A1D"]

        when:
        def nameQuery = FleetQueryTranslator.nameTextQuery(fq, "acme", SELECT)
        def idQuery = FleetQueryTranslator.identifierTextQuery(fq, "acme", SELECT)

        then: "blank and duplicate values are dropped, the rest trimmed"
        nameQuery.names*.value == ["70B3D5D0003E8A1D", "70B3D5448FA36861"]

        and: "one OR group over every identifier and value, no nested groups"
        idQuery.attributes.operator == org.openremote.model.query.LogicGroup.Operator.OR
        idQuery.attributes.items.size() == 2 * FleetQueryTranslator.IDENTIFIER_ATTRIBUTES.length
        idQuery.attributes.items*.value*.value as Set == ["70B3D5D0003E8A1D", "70B3D5448FA36861"] as Set
        idQuery.attributes.groups == null
    }

    def "only repeated, non-negated text filters on one attribute are ORed"() {
        given:
        def negated = filter("meter_id", FleetQuery.Operator.EQUALS, "X")
        negated.negate = true
        def fq = new FleetQuery()
        fq.attributes = [
                filter("devEui", FleetQuery.Operator.CONTAINS, "A1"),
                filter("DEVEUI", FleetQuery.Operator.STARTS_WITH, "B2"),
                filter("battery_level", FleetQuery.Operator.GREATER_THAN, 10),
                filter("battery_level", FleetQuery.Operator.LESS_THAN, 50),
                filter("low_battery", FleetQuery.Operator.EQUALS, true),
                filter("tamper", FleetQuery.Operator.EQUALS, true),
                filter("meter_id", FleetQuery.Operator.NOT_EQUALS, "Y"),
                negated,
                filter("external_id", FleetQuery.Operator.EQUALS, "single"),
        ]

        when:
        def groups = FleetQueryTranslator.anyOfFilters(fq)
        def q = FleetQueryTranslator.filterQuery(fq, "acme", SELECT)

        then: "attribute names group ignoring case"
        groups.keySet() == ["deveui"] as Set
        groups["deveui"]*.value == ["A1", "B2"]

        and: "the ORed filters leave SQL, everything else is still ANDed there"
        q.attributes.operator == org.openremote.model.query.LogicGroup.Operator.AND
        q.attributes.items*.name*.value == ["battery_level", "battery_level", "low_battery", "tamper",
                                            "meter_id", "meter_id", "external_id"]
        q.attributes.groups == null
    }

    def "the ORed attributes are added to the select so they can be matched"() {
        given:
        def fq = new FleetQuery()
        fq.attributes = [filter("custom_code", FleetQuery.Operator.EQUALS, "a"),
                         filter("custom_code", FleetQuery.Operator.EQUALS, "b")]

        expect:
        FleetQueryTranslator.withAnyOfAttributes(SELECT, fq) as List == (SELECT as List) + ["custom_code"]
        FleetQueryTranslator.withAnyOfAttributes(SELECT, new FleetQuery()) as List == SELECT as List
    }

    def "the Java OR filter keeps assets matching any value of every group"() {
        given:
        def a = new ThingAsset("A")
        a.getAttributes().addOrReplace(new org.openremote.model.attribute.Attribute<>("devEui", org.openremote.model.value.ValueType.TEXT, "70b3d5d0003e8a1d"))
        a.getAttributes().addOrReplace(new org.openremote.model.attribute.Attribute<>("meter_id", org.openremote.model.value.ValueType.TEXT, "M-1"))
        def b = new ThingAsset("B")
        b.getAttributes().addOrReplace(new org.openremote.model.attribute.Attribute<>("devEui", org.openremote.model.value.ValueType.TEXT, "70B3D5448FA36861"))
        b.getAttributes().addOrReplace(new org.openremote.model.attribute.Attribute<>("meter_id", org.openremote.model.value.ValueType.TEXT, "M-2"))
        def c = new ThingAsset("C")
        List<Asset<?>> assets = [a, b, c]
        def fq = new FleetQuery()
        fq.attributes = [filter("devEui", FleetQuery.Operator.EQUALS, "70B3D5D0003E8A1D"),
                         filter("devEui", FleetQuery.Operator.CONTAINS, "448FA")]

        expect: "values match ignoring case, an asset without the attribute never matches"
        FleetQueryTranslator.filterByAnyOf(assets, fq)*.name == ["A", "B"]

        when: "a second group must match too"
        fq.attributes = fq.attributes + [filter("METER_ID", FleetQuery.Operator.EQUALS, "M-2"),
                                         filter("meter_id", FleetQuery.Operator.EQUALS, "M-9")]

        then:
        FleetQueryTranslator.filterByAnyOf(assets, fq)*.name == ["B"]

        and: "without groups the list is returned as is"
        FleetQueryTranslator.filterByAnyOf(assets, new FleetQuery()).is(assets)
    }

    def "a name under a site matches any of several texts"() {
        given:
        List<Asset<?>> assets = [new ThingAsset("Kotelna sever"), new ThingAsset("Hall B"), new ThingAsset("Office")]

        expect:
        FleetQueryTranslator.filterByName(assets, ["kotelna", "hall"])*.name == ["Kotelna sever", "Hall B"]
    }
}
