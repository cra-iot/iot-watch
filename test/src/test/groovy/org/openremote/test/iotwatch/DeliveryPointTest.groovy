package org.openremote.test.iotwatch

import org.openremote.model.electricmeter.ElectricMeterAsset
import org.openremote.model.gasmeter.GasMeterAsset
import org.openremote.model.heatmeter.HeatMeterAsset
import org.openremote.model.meter.DeliveryPoint
import org.openremote.model.util.ValueUtil
import org.openremote.model.value.MetaItemType
import org.openremote.model.value.ValueConstraint
import org.openremote.model.value.ValueType
import org.openremote.model.watermeter.WaterMeterAsset
import spock.lang.Specification

import java.util.regex.Pattern

class DeliveryPointTest extends Specification {

    // Constructing a real Asset requires the asset model to be initialised
    // (Asset(String) -> ValueUtil.initialiseAssetAttributes); this module never starts a
    // container, so trigger the same one-time initialisation the manager does at startup.
    def setupSpec() {
        ValueUtil.initialise(null)
    }

    static final Pattern RX = Pattern.compile(DeliveryPoint.PATTERN)

    def "every meter type declares the same optional text attribute"() {
        expect:
        descriptor.name == "delivery_point_id"
        descriptor.name == DeliveryPoint.ATTRIBUTE_NAME
        descriptor.type == ValueType.TEXT
        descriptor.isOptional()

        where:
        descriptor << [
            ElectricMeterAsset.DELIVERY_POINT_ID_ATTRIBUTE_DESCRIPTOR,
            GasMeterAsset.DELIVERY_POINT_ID_ATTRIBUTE_DESCRIPTOR,
            HeatMeterAsset.DELIVERY_POINT_ID_ATTRIBUTE_DESCRIPTOR,
            WaterMeterAsset.DELIVERY_POINT_ID_ATTRIBUTE_DESCRIPTOR
        ]
    }

    def "the descriptor carries the pattern constraint and the restricted-read meta"() {
        given:
        def d = GasMeterAsset.DELIVERY_POINT_ID_ATTRIBUTE_DESCRIPTOR

        expect: "the format is enforced by the model, not by the operator's care"
        d.constraints.any { it instanceof ValueConstraint.Pattern && it.regexp == DeliveryPoint.PATTERN }

        and: "the label is the exact Global Constraint, not an approximation of it"
        d.getMetaValue(MetaItemType.LABEL).orElse(null) == "Identifikátor odběrného místa (EAN/EIC)"

        and: "customer (restricted) users can read their own delivery point"
        d.getMetaValue(MetaItemType.ACCESS_RESTRICTED_READ).orElse(false)

        and: "the operator sets this value, so it is not read-only"
        !d.meta.has(MetaItemType.READ_ONLY)

        and: "it is an identifier, not a measurement — no rule traffic, no data points"
        !d.meta.has(MetaItemType.RULE_STATE)
        !d.meta.has(MetaItemType.STORE_DATA_POINTS)
    }

    def "an asset that has no delivery point reads as empty, not as a failure"() {
        given: "an optional descriptor is never materialised, so no asset has this attribute until someone adds it"
        def asset = new GasMeterAsset("g")

        expect:
        !asset.getDeliveryPointId().isPresent()
    }

    def "the pattern accepts '#code'"() {
        expect:
        RX.matcher(code).matches()

        where:
        code << [
            "859182400000000001",   // electricity EAN
            "859182400000000002",   // electricity EAN
            "27ZG000Z0000000A",     // gas EIC
            "27ZG000Z00000000",     // gas EIC ending in a digit
            "27ZG-00Z0000000A"      // EIC with a hyphen in the free part — legal, must not be rejected
        ]
    }

    def "the pattern rejects '#code' — an unsearchable value must not reach the database"() {
        expect:
        !RX.matcher(code).matches()

        where:
        code << [
            " 859182400000000001",   // leading whitespace
            "859182400000000001 ",   // trailing whitespace
            "85918240000000000",     // 17 digits
            "8591824000000000011",   // 19 digits
            "27zg000z0000000a",      // lowercase EIC
            "27ZG000Z0000000",       // 15 characters
            "859182400000000001|27ZG000Z0000000A",  // two codes in one field
            ""
        ]
    }
}
