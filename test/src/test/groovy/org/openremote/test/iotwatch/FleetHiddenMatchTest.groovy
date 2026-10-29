package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.FleetResourceImpl
import org.openremote.model.attribute.Attribute
import org.openremote.model.iotwatch.FleetQuery
import org.openremote.model.value.ValueType
import org.openremote.model.watermeter.WaterMeterAsset
import spock.lang.Specification

// Runs without the container: the restricted-caller check is a pure function of the asset as the
// caller may read it. Attributes absent from the asset stand for attributes the select withheld.
class FleetHiddenMatchTest extends Specification {

    static WaterMeterAsset visible(Map<String, String> attributes) {
        def asset = new WaterMeterAsset("Water meter A")
        // A fresh asset carries its required attributes; keep only the ones under test.
        asset.getAttributes().clear()
        attributes.each { name, value -> asset.getAttributes().addOrReplace(new Attribute<>(name, ValueType.TEXT, value)) }
        return asset
    }

    def "an asset the caller cannot read at all never matches"() {
        expect:
        !FleetResourceImpl.matchesVisibly(null, [] as Set, [])
    }

    def "a filter on a readable attribute keeps the asset, ignoring case"() {
        expect:
        FleetResourceImpl.matchesVisibly(visible([leakage: "true"]), ["LEAKAGE"] as Set, [])
    }

    def "a filter on a withheld attribute drops the asset"() {
        expect: "the SQL match on chip_id cannot be explained by anything the caller may read"
        !FleetResourceImpl.matchesVisibly(visible([leakage: "true"]), ["leakage", "chip_id"] as Set, [])
    }

    def "free text matching the name keeps the asset even when no identifier is readable"() {
        expect:
        FleetResourceImpl.matchesVisibly(visible([:]), [] as Set, ["meter a"])
    }

    def "free text matching a readable identifier keeps the asset"() {
        expect:
        FleetResourceImpl.matchesVisibly(visible([devEui: "00112233AABBCC01"]), [] as Set, ["aabbcc01"])
    }

    def "free text that only a withheld identifier could match drops the asset"() {
        expect: "external_id matched in SQL, but the select withheld it"
        !FleetResourceImpl.matchesVisibly(visible([devEui: "00112233AABBCC01"]), [] as Set, ["TEST-HIDDEN"])
    }

    def "filtered names are trimmed as sent"() {
        given:
        def filter = new FleetQuery.AttributeFilter()
        filter.name = " leakage "
        def query = new FleetQuery()
        query.attributes = [filter]

        expect:
        FleetResourceImpl.filteredAttributeNames(query) == ["leakage"] as Set
        FleetResourceImpl.filteredAttributeNames(new FleetQuery()).isEmpty()
    }
}
