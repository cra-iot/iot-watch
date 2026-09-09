package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.DecoderRegistry
import org.openremote.manager.iotwatch.GnssDecoder
import org.openremote.manager.iotwatch.SameNameDecoder
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.gasmeter.GasMeterAsset
import org.openremote.model.heatmeter.HeatMeterAsset
import org.openremote.model.attribute.Attribute
import org.openremote.model.tracker.TrackerAsset
import org.openremote.model.util.ValueUtil
import org.openremote.model.watermeter.WaterMeterAsset
import spock.lang.Specification

class DecoderRegistryTest extends Specification {

    // Constructing a real Asset (WaterMeterAsset/TrackerAsset/ThingAsset) requires the asset
    // model to be initialised (Asset(String) -> ValueUtil.initialiseAssetAttributes); this test
    // module never spins up a container, so trigger the same one-time, container-less
    // initialisation the manager normally does at startup.
    def setupSpec() {
        ValueUtil.initialise(null)
    }

    DecoderRegistry registry = new DecoderRegistry()

    def "builds a same-name plan intersecting candidates with provisioned attributes"() {
        given:
        def asset = new WaterMeterAsset("W")
        asset.getAttributes().clear()   // control the provisioned set exactly
        asset.getAttributes().getOrCreate(WaterMeterAsset.CURRENT_READING_ATTRIBUTE_DESCRIPTOR)
        asset.getAttributes().getOrCreate(WaterMeterAsset.LEAKAGE_ATTRIBUTE_DESCRIPTOR)

        when:
        def plan = registry.planFor(asset)

        then:
        plan.isPresent()
        plan.get().decoder() instanceof SameNameDecoder
        plan.get().targetNames() == ["currentReading", "leakage"] as Set
    }

    def "builds a gnss plan for trackers, gated by provisioned attributes"() {
        given:
        def asset = new TrackerAsset("T")
        asset.getAttributes().clear()
        asset.getAttributes().getOrCreate(TrackerAsset.BATTERY_ATTRIBUTE_DESCRIPTOR)

        when:
        def plan = registry.planFor(asset)

        then:
        plan.isPresent()
        plan.get().decoder() instanceof GnssDecoder
        plan.get().targetNames() == ["battery"] as Set   // location not provisioned here
    }

    def "builds a same-name plan for heat meters"() {
        given:
        def asset = new HeatMeterAsset("H")
        asset.getAttributes().clear()
        asset.getAttributes().getOrCreate(HeatMeterAsset.ENERGY_HEATING_ATTRIBUTE_DESCRIPTOR)
        asset.getAttributes().getOrCreate(HeatMeterAsset.TEMPERATURE_FLOW_ATTRIBUTE_DESCRIPTOR)
        asset.getAttributes().getOrCreate(HeatMeterAsset.METER_ERROR_ATTRIBUTE_DESCRIPTOR)

        when:
        def plan = registry.planFor(asset)

        then:
        plan.isPresent()
        plan.get().decoder() instanceof SameNameDecoder
        plan.get().targetNames() == ["energy_heating", "temperature_flow", "meter_error"] as Set
    }

    // Guards the contract with the platform decoder: these are exactly the keys a heat-meter
    // decoder emits (the Maddalena Microclima / Engelmann LoRa module is the first one), so a
    // rename on either side breaks this test rather than silently dropping measurements.
    def "decodes the heat-meter key set onto a fully provisioned asset"() {
        given: "an asset provisioned with every attribute this device fills"
        def asset = new HeatMeterAsset("H")
        [HeatMeterAsset.ENERGY_HEATING_ATTRIBUTE_DESCRIPTOR,
         HeatMeterAsset.VOLUME_ATTRIBUTE_DESCRIPTOR,
         HeatMeterAsset.POWER_ATTRIBUTE_DESCRIPTOR,
         HeatMeterAsset.FLOW_RATE_ATTRIBUTE_DESCRIPTOR,
         HeatMeterAsset.TEMPERATURE_FLOW_ATTRIBUTE_DESCRIPTOR,
         HeatMeterAsset.TEMPERATURE_RETURN_ATTRIBUTE_DESCRIPTOR,
         HeatMeterAsset.METER_ID_ATTRIBUTE_DESCRIPTOR,
         HeatMeterAsset.ERROR_FLAGS_ATTRIBUTE_DESCRIPTOR,
         HeatMeterAsset.METER_ERROR_ATTRIBUTE_DESCRIPTOR,
         HeatMeterAsset.METER_READ_ERROR_ATTRIBUTE_DESCRIPTOR].each {
            asset.getAttributes().getOrCreate(it)
        }
        def plan = registry.planFor(asset).get()
        def message = [data_decoded: [
            decoded          : true,
            energy_heating   : 1234.567d,
            volume           : 8901.2d,
            power            : 12.5d,
            flow_rate        : 0.85d,
            temperature_flow : 71.4d,
            temperature_return: 43.9d,
            meter_id         : "87654321",
            error_flags      : "0x04",
            meter_error      : true,
            meter_read_error : false,
            message_format   : "0x24",   // diagnostic, no attribute: readable from rawValue
            energy_vif_raw   : "0x06"    // diagnostic, no attribute: readable from rawValue
        ]]

        when:
        def events = plan.decoder().decode("asset1", message, plan.targetNames(), 1000L)
        def byName = events.collectEntries { [it.ref.name, it.value.get()] }

        then:
        byName["energy_heating"] == 1234.567d
        byName["volume"] == 8901.2d
        byName["power"] == 12.5d
        byName["flow_rate"] == 0.85d
        byName["temperature_flow"] == 71.4d
        byName["temperature_return"] == 43.9d
        byName["meter_id"] == "87654321"
        byName["error_flags"] == "0x04"
        byName["meter_error"] == true
        byName["meter_read_error"] == false

        and: "the decode flag and the device diagnostics are not attributes"
        !byName.containsKey("decoded")
        !byName.containsKey("message_format")
        !byName.containsKey("energy_vif_raw")
    }

    def "builds a same-name plan for gas meters"() {
        given:
        def asset = new GasMeterAsset("G")
        asset.getAttributes().clear()
        asset.getAttributes().getOrCreate(GasMeterAsset.VOLUME_ATTRIBUTE_DESCRIPTOR)
        asset.getAttributes().getOrCreate(GasMeterAsset.VOLUME_STANDARD_ATTRIBUTE_DESCRIPTOR)
        asset.getAttributes().getOrCreate(GasMeterAsset.VALVE_CLOSED_ATTRIBUTE_DESCRIPTOR)

        when:
        def plan = registry.planFor(asset)

        then:
        plan.isPresent()
        plan.get().decoder() instanceof SameNameDecoder
        plan.get().targetNames() == ["volume", "volume_standard", "valve_closed"] as Set
    }

    // Guards the contract with the platform decoder the same way the heat-meter spec above
    // does: these are the keys a gas-meter decoder emits, including the conversion chain a
    // meter with a volume converter reports, so a rename on either side breaks this test
    // rather than silently dropping measurements.
    def "decodes the gas-meter key set onto a fully provisioned asset"() {
        given: "an asset provisioned with every attribute this device fills"
        def asset = new GasMeterAsset("G")
        [GasMeterAsset.VOLUME_ATTRIBUTE_DESCRIPTOR,
         GasMeterAsset.VOLUME_PREVIOUS_MONTH_ATTRIBUTE_DESCRIPTOR,
         GasMeterAsset.VOLUME_STANDARD_ATTRIBUTE_DESCRIPTOR,
         GasMeterAsset.ENERGY_ATTRIBUTE_DESCRIPTOR,
         GasMeterAsset.FLOW_RATE_ATTRIBUTE_DESCRIPTOR,
         GasMeterAsset.PRESSURE_ATTRIBUTE_DESCRIPTOR,
         GasMeterAsset.GAS_TEMPERATURE_ATTRIBUTE_DESCRIPTOR,
         GasMeterAsset.BATTERY_LEVEL_ATTRIBUTE_DESCRIPTOR,
         GasMeterAsset.METER_ID_ATTRIBUTE_DESCRIPTOR,
         GasMeterAsset.ERROR_FLAGS_ATTRIBUTE_DESCRIPTOR,
         GasMeterAsset.METER_ERROR_ATTRIBUTE_DESCRIPTOR,
         GasMeterAsset.VALVE_CLOSED_ATTRIBUTE_DESCRIPTOR].each {
            asset.getAttributes().getOrCreate(it)
        }
        def plan = registry.planFor(asset).get()
        def message = [data_decoded: [
            decoded              : true,
            volume               : 4567.891d,
            volume_previous_month: 4400.0d,
            volume_standard      : 4321.098d,
            energy               : 47532.6d,
            flow_rate            : 1.25d,
            pressure             : 102.4d,
            gas_temperature      : 11.7d,
            battery_level        : 87,
            meter_id             : "12345678",
            error_flags          : "0x02",
            meter_error          : false,
            valve_closed         : true,
            conversion_factor    : 0.982d   // diagnostic, no attribute: readable from rawValue
        ]]

        when:
        def events = plan.decoder().decode("asset1", message, plan.targetNames(), 1000L)
        def byName = events.collectEntries { [it.ref.name, it.value.get()] }

        then:
        byName["volume"] == 4567.891d
        byName["volume_previous_month"] == 4400.0d
        byName["volume_standard"] == 4321.098d
        byName["energy"] == 47532.6d
        byName["flow_rate"] == 1.25d
        byName["pressure"] == 102.4d
        byName["gas_temperature"] == 11.7d
        byName["battery_level"] == 87
        byName["meter_id"] == "12345678"
        byName["error_flags"] == "0x02"
        byName["meter_error"] == false
        byName["valve_closed"] == true

        and: "the decode flag and the unmodelled key are not attributes"
        !byName.containsKey("decoded")
        !byName.containsKey("conversion_factor")
    }

    def "returns empty for an unregistered asset type"() {
        expect:
        registry.planFor(new ThingAsset("C")).isEmpty()
    }

    def "returns empty when no candidate attribute is provisioned"() {
        given:
        def asset = new WaterMeterAsset("W")
        asset.getAttributes().clear()   // no measurement attributes at all

        expect:
        registry.planFor(asset).isEmpty()
    }
}
