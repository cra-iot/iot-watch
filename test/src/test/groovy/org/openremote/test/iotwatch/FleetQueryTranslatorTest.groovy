package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.FleetQueryTranslator
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
        fq.text = "kotelna"
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
        fq.text = "kotelna"
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
}
