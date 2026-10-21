package org.openremote.model.iotwatch;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The declared primary registers: the one attribute that stands for "the reading" on a type this
 * project knows about. It lives in model/ beside the asset classes, so adding a meter type is a
 * one-line edit in the module that already defines it and neither manager/ nor ui/ holds a copy.
 *
 * A type absent from this map is not unsupported. FleetColumnResolver falls back to the asset
 * model, and the user can override the column set in the UI; declaring a type only removes the
 * guess. Never add a lookup here for an attribute the model could answer for itself.
 */
public final class FleetColumns {

    public static final Map<String, String> PRIMARY_REGISTERS;

    static {
        Map<String, String> registers = new LinkedHashMap<>();
        registers.put("WaterMeterAsset", "currentReading");
        registers.put("GasMeterAsset", "volume");
        registers.put("ElectricMeterAsset", "energy_active_total");
        registers.put("HeatMeterAsset", "energy_heating");
        PRIMARY_REGISTERS = Collections.unmodifiableMap(registers);
    }

    /**
     * Attributes fetched for every row whatever the type: identity and the battery. These are
     * genuinely cross-type - every meter asset in this project declares them under the same
     * names - which is the only reason they may be a constant.
     *
     * rawValue is deliberately absent: it is hidden from restricted users and its value must
     * never be loaded into a row. FleetResourceImpl reads its timestamp in a separate lookup.
     */
    public static final String[] INFRASTRUCTURE_ATTRIBUTES = {
            "delivery_point_id", "meter_id", "devEui", "external_id", "battery_level"
    };

    /** The ingest marker; its timestamp is the row's lastSeen. Never selected into a row. */
    public static final String RAW_VALUE = "rawValue";

    /**
     * Boolean attributes that report a state, not a fault, so a true value is not an alarm.
     * Every other boolean on an asset is treated as an alarm flag. Add a name here when a new
     * type declares a state boolean; never encode alarms themselves as a list.
     */
    public static final Set<String> STATE_BOOLEANS = Set.of("valve_closed");

    private FleetColumns() {
    }
}
