package org.openremote.test.iotwatch

import org.openremote.model.iotwatch.FleetQuery
import org.openremote.model.iotwatch.FleetQueryResult
import org.openremote.model.iotwatch.FleetRow
import org.openremote.model.util.ValueUtil
import spock.lang.Specification

class FleetDtoTest extends Specification {

    def setupSpec() {
        ValueUtil.initialise(null)
    }

    def "FleetQuery round-trips through JSON with every field populated"() {
        given:
        def json = '''{
          "text": ["kotelna"],
          "types": ["GasMeterAsset"],
          "parentIds": ["abc"],
          "recursive": true,
          "attributes": [{"name": "battery_level", "operator": "LESS_THAN", "value": 20}],
          "seen": {"olderThan": "PT24H"},
          "groupBy": "PARENT",
          "sort": {"field": "lastSeen", "descending": true},
          "columns": ["volume", "gas_temperature"],
          "offset": 0,
          "limit": 50
        }'''

        when:
        def q = ValueUtil.JSON.readValue(json, FleetQuery.class)

        then:
        q.text == ["kotelna"]
        q.types == ["GasMeterAsset"]
        q.parentIds == ["abc"]
        q.recursive
        q.attributes.size() == 1
        q.attributes[0].name == "battery_level"
        q.attributes[0].operator == FleetQuery.Operator.LESS_THAN
        q.seen.olderThan == "PT24H"
        q.groupBy == FleetQuery.GroupBy.PARENT
        q.sort.field == "lastSeen"
        q.sort.descending
        q.columns == ["volume", "gas_temperature"]
        q.limit == 50
    }

    def "an empty FleetQuery deserialises with safe defaults"() {
        when:
        def q = ValueUtil.JSON.readValue("{}", FleetQuery.class)

        then:
        q.text == null
        q.types == null
        q.recursive == false
        q.groupBy == FleetQuery.GroupBy.NONE
        q.columns == null
        q.offset == 0
        q.limit == 0
    }

    def "FleetRow omits nulls and empty alarms on the wire, so the UI must treat absent as empty"() {
        given: "ValueUtil.JSON is NON_NULL and does not write empty arrays"
        def row = new FleetRow()
        row.id = "abc"
        row.name = "Kotelna"
        row.type = "GasMeterAsset"

        when:
        def node = ValueUtil.JSON.valueToTree(row)

        then:
        node.get("id").asText() == "abc"
        !node.has("primaryName")
        !node.has("lastSeen")
        !node.has("alarms")
    }

    def "a null register value is dropped from the value map on the wire"() {
        given: "an asset carrying the attribute that has never received a value"
        def row = new FleetRow()
        row.primaryName = "volume"
        row.values.put("volume", null)

        when:
        def back = ValueUtil.JSON.readValue(ValueUtil.JSON.writeValueAsString(row), FleetRow.class)

        then: "Map content is NON_NULL too; 'no value yet' and 'no such attribute' both render an em dash"
        !back.values.containsKey("volume")
    }

    def "FleetQueryResult carries the resolved columns with units as OpenRemote unit keys"() {
        given:
        def column = new FleetQueryResult.ColumnDescriptor()
        column.name = "volume"
        column.label = "Objem plynu"
        column.units = ["metre", "cubed"] as String[]
        column.numeric = true
        column.primary = true
        column.selected = true
        column.assetType = "GasMeterAsset"
        def result = new FleetQueryResult()
        result.columns = [column]

        when:
        def node = ValueUtil.JSON.valueToTree(result)

        then:
        node.get("columns").get(0).get("name").asText() == "volume"
        node.get("columns").get(0).get("units").get(0).asText() == "metre"
        node.get("columns").get(0).get("units").get(1).asText() == "cubed"
        node.get("columns").get(0).get("primary").asBoolean()
    }
}
